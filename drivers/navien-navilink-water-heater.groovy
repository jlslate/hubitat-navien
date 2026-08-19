/**
 *  Navien NaviLink Water Heater
 *
 *  One heating channel of a NaviLink gateway. The parent gateway device owns the cloud
 *  connection and calls updateStatus() on this device whenever Navien sends a status
 *  frame; commands go back out through the parent.
 *
 *  Wire values from Navien are scaled, and the scaling depends on how the unit was
 *  commissioned (Celsius or Fahrenheit) and on the unit family. All of that is decoded
 *  here, then converted to the hub's temperature scale.
 *
 *  Licensed under the Apache License, Version 2.0
 */

import groovy.transform.Field

// Unit families that report gas usage with an extra factor of ten.
@Field static final List<Integer> HIGH_GAS_SCALE_UNITS = [6, 8, 13, 14]   // NFB, NFC, NCB-H, NVW

metadata {
    definition(name: "Navien NaviLink Water Heater", namespace: "jlslate", author: "jlslate") {
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "ThermostatHeatingSetpoint"
        capability "Refresh"
        capability "Sensor"
        capability "Actuator"

        attribute "operatingState", "enum", ["heating", "idle", "off"]
        attribute "inletTemperature", "number"
        attribute "outletTemperature", "number"
        attribute "flowRate", "number"
        attribute "gasUsage", "number"
        attribute "cumulativeGasUsage", "number"
        attribute "onDemand", "enum", ["on", "off"]
        attribute "minSetpoint", "number"
        attribute "maxSetpoint", "number"
        attribute "errorCode", "number"
        attribute "lastUpdate", "string"

        command "onDemandOn"
        command "onDemandOff"
        command "setDHWTemperature", [[name: "Temperature*", type: "NUMBER", description: "Domestic hot water setpoint"]]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptive text logging", defaultValue: true
    }
}

// ===================================================================================
// Lifecycle
// ===================================================================================

def installed() {
    logDebug "installed()"
}

def updated() {
    logDebug "updated()"
}

// ===================================================================================
// Commands
// ===================================================================================

/**
 * Every command is relayed through the gateway device. A channel device added by hand
 * has no gateway to relay through.
 */
private Boolean gatewayPresent() {
    if (parent != null) return true
    log.error "${device.displayName} was not created by the Navien NaviLink gateway, so it has nothing to " +
              "send commands through. Delete it and let the gateway device create its channels."
    return false
}

def on() {
    if (txtEnable != false) log.info "${device.displayName}: power on"
    if (!gatewayPresent()) return
    parent.setPower(channelNumber(), true)
}

def off() {
    if (txtEnable != false) log.info "${device.displayName}: power off"
    if (!gatewayPresent()) return
    parent.setPower(channelNumber(), false)
}

def onDemandOn() {
    if (txtEnable != false) log.info "${device.displayName}: on-demand recirculation on"
    if (!gatewayPresent()) return
    parent.setOnDemand(channelNumber(), true)
}

def onDemandOff() {
    if (txtEnable != false) log.info "${device.displayName}: on-demand recirculation off"
    if (!gatewayPresent()) return
    parent.setOnDemand(channelNumber(), false)
}

def setHeatingSetpoint(temperature) {
    setDHWTemperature(temperature)
}

def setDHWTemperature(temperature) {
    if (temperature == null) {
        log.warn "${device.displayName}: setDHWTemperature called without a temperature"
        return
    }

    BigDecimal requested = temperature as BigDecimal
    BigDecimal minAllowed = device.currentValue("minSetpoint") as BigDecimal
    BigDecimal maxAllowed = device.currentValue("maxSetpoint") as BigDecimal
    BigDecimal target = requested
    if (minAllowed != null && target < minAllowed) target = minAllowed
    if (maxAllowed != null && target > maxAllowed) target = maxAllowed
    if (target != requested) {
        log.warn "${device.displayName}: ${requested} is outside the unit's range (${minAllowed}-${maxAllowed}); using ${target}"
    }

    // Convert from the hub's scale to the unit's scale, then to Navien's wire encoding.
    BigDecimal deviceScaleTemp = toDeviceScale(target)
    Integer raw = (temperatureType() == 1)
                  ? (deviceScaleTemp * 2).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
                  : deviceScaleTemp.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()

    if (txtEnable != false) log.info "${device.displayName}: setpoint ${target}°${location.temperatureScale}"
    if (!gatewayPresent()) return
    parent.setTemperature(channelNumber(), raw)
}

def refresh() {
    if (!gatewayPresent()) return
    parent.refreshChannel(channelNumber())
}

// ===================================================================================
// Called by the parent gateway
// ===================================================================================

void setLimits(BigDecimal minTemp, BigDecimal maxTemp, Integer tempType) {
    // minTemp/maxTemp arrive already decoded, in the unit's own scale.
    if (minTemp != null) sendEvent(name: "minSetpoint", value: round1(toHubScale(minTemp, tempType)), unit: "°${location.temperatureScale}")
    if (maxTemp != null) sendEvent(name: "maxSetpoint", value: round1(toHubScale(maxTemp, tempType)), unit: "°${location.temperatureScale}")
}

void updateStatus(Map status) {
    logDebug "updateStatus: ${status}"
    Integer tempType = temperatureType()
    Integer unitType = (status.unitType ?: 0) as Integer
    List units = (status.unitInfo?.unitStatusList ?: []) as List

    Boolean powerOn = ((status.powerStatus ?: 0) as Integer) == 1
    sendEventIfChanged("switch", powerOn ? "on" : "off")

    Boolean onDemand = ((status.onDemandUseFlag ?: 0) as Integer) == 1
    sendEventIfChanged("onDemand", onDemand ? "on" : "off")

    if (status.DHWSettingTemp != null) {
        BigDecimal setpoint = toHubScale(decodeTemp(status.DHWSettingTemp, tempType), tempType)
        sendEventIfChanged("heatingSetpoint", round1(setpoint), "°${location.temperatureScale}")
    }

    // Per-unit values: temperatures average across units, flow and gas sum.
    BigDecimal outlet = null, inlet = null
    BigDecimal flow = 0, gasNow = 0, gasTotal = 0
    Integer errorCode = 0

    if (units) {
        BigDecimal outletSum = 0, inletSum = 0
        units.each { u ->
            outletSum += decodeTemp(u.currentOutletTemp, tempType) ?: 0
            inletSum += decodeTemp(u.currentInletTemp, tempType) ?: 0
            flow += decodeFlow(u.DHWFlowRate, tempType)
            gasNow += decodeInstantGas(u.gasInstantUsage, tempType, unitType)
            gasTotal += decodeAccumulatedGas(u.accumulatedGasUsage, tempType)
            Integer ec = (u.errorCode ?: 0) as Integer
            if (ec > 0) errorCode = ec
        }
        outlet = outletSum / units.size()
        inlet = inletSum / units.size()
    } else {
        if (status.avgOutletTemp != null) outlet = decodeTemp(status.avgOutletTemp, tempType)
        if (status.avgInletTemp != null) inlet = decodeTemp(status.avgInletTemp, tempType)
    }

    if (outlet != null) {
        BigDecimal value = round1(toHubScale(outlet, tempType))
        sendEventIfChanged("temperature", value, "°${location.temperatureScale}")
        sendEventIfChanged("outletTemperature", value, "°${location.temperatureScale}")
    }
    if (inlet != null) {
        sendEventIfChanged("inletTemperature", round1(toHubScale(inlet, tempType)), "°${location.temperatureScale}")
    }

    sendEventIfChanged("flowRate", round1(flow), tempType == 1 ? "LPM" : "GPM")
    sendEventIfChanged("gasUsage", round1(gasNow), tempType == 1 ? "kcal/h" : "BTU/h")
    sendEventIfChanged("cumulativeGasUsage", round1(gasTotal), tempType == 1 ? "m³" : "ft³")

    Integer channelError = (status.errorCode ?: 0) as Integer
    if (channelError > 0) errorCode = channelError
    sendEventIfChanged("errorCode", errorCode)

    String opState = !powerOn ? "off" : (gasNow > 0 || flow > 0) ? "heating" : "idle"
    sendEventIfChanged("operatingState", opState)

    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// ===================================================================================
// Decoding
// ===================================================================================

/** Celsius units send temperatures in half degrees; Fahrenheit units send whole degrees. */
private BigDecimal decodeTemp(value, Integer tempType) {
    if (value == null) return null
    BigDecimal v = value as BigDecimal
    return (tempType == 1) ? v / 2.0 : v
}

/** Flow arrives as tenths of a litre/min (Celsius) or in hundredths of a gallon-ish unit (Fahrenheit). */
private BigDecimal decodeFlow(value, Integer tempType) {
    if (value == null) return 0
    BigDecimal v = value as BigDecimal
    return (tempType == 1) ? v / 10.0 : v / 37.85
}

private BigDecimal decodeInstantGas(value, Integer tempType, Integer unitType) {
    if (value == null) return 0
    BigDecimal v = value as BigDecimal
    if (tempType == 1) {
        BigDecimal factor = HIGH_GAS_SCALE_UNITS.contains(unitType) ? 100 : 10
        return (v * factor) / 10.0
    }
    BigDecimal factor = HIGH_GAS_SCALE_UNITS.contains(unitType) ? 10 : 1
    return v * factor * 3.968
}

private BigDecimal decodeAccumulatedGas(value, Integer tempType) {
    if (value == null) return 0
    BigDecimal v = value as BigDecimal
    return (tempType == 1) ? v / 10.0 : (v * 35.314667) / 10.0
}

// ===================================================================================
// Scale conversion
// ===================================================================================

/** Convert a temperature in the unit's own scale to the hub's scale. */
private BigDecimal toHubScale(BigDecimal value, Integer tempType = temperatureType()) {
    if (value == null) return null
    String deviceScale = (tempType == 1) ? "C" : "F"
    if (deviceScale == location.temperatureScale) return value
    return (deviceScale == "C") ? (value * 9 / 5) + 32 : (value - 32) * 5 / 9
}

/** Convert a temperature in the hub's scale to the unit's scale. */
private BigDecimal toDeviceScale(BigDecimal value) {
    String deviceScale = (temperatureType() == 1) ? "C" : "F"
    if (deviceScale == location.temperatureScale) return value
    return (deviceScale == "C") ? (value - 32) * 5 / 9 : (value * 9 / 5) + 32
}

// ===================================================================================
// Helpers
// ===================================================================================

private Integer channelNumber()   { return (getDataValue("channelNumber") ?: "1") as Integer }
private Integer temperatureType() { return (getDataValue("temperatureType") ?: "2") as Integer }

private BigDecimal round1(BigDecimal value) {
    if (value == null) return null
    return value.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private void sendEventIfChanged(String name, value, String unit = null) {
    def current = device.currentValue(name)
    Boolean changed = (current == null) || (current.toString() != value.toString())
    if (!changed) return
    Map evt = [name: name, value: value]
    if (unit) evt.unit = unit
    if (txtEnable != false) evt.descriptionText = "${device.displayName} ${name} is ${value}${unit ?: ''}"
    sendEvent(evt)
    if (txtEnable != false) log.info evt.descriptionText
}

private void logDebug(String msg) {
    if (logEnable != false) log.debug msg
}
