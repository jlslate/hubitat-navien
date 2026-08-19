/**
 *  Navien NaviLink Gateway
 *
 *  Owns the cloud connection for one NaviLink gateway.
 *
 *  Navien's cloud carries all live data over AWS IoT Core. The mobile app authenticates
 *  with the NaviLink REST API, receives temporary AWS IAM credentials, and connects to
 *  AWS IoT over MQTT-on-WebSockets using a SigV4 pre-signed URL. This driver does the
 *  same thing: it asks the parent app for credentials, signs the wss:// URL itself and
 *  hands it to Hubitat's MQTT client.
 *
 *  If the hub's MQTT client refuses the wss:// URL, the driver falls back to the AWS IoT
 *  HTTPS publish endpoint for control commands (SigV4 signed, port 8443). Control works
 *  in that mode, but status cannot be received, because HTTPS publish has no subscribe.
 *
 *  Licensed under the Apache License, Version 2.0
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.text.SimpleDateFormat

@Field static final Integer CMD_CHANNEL_INFO   = 16777217
@Field static final Integer CMD_CHANNEL_STATUS = 16777220
@Field static final Integer CMD_POWER          = 33554433
@Field static final Integer CMD_TEMPERATURE    = 33554435
@Field static final Integer CMD_ON_DEMAND      = 33554437

metadata {
    definition(name: "Navien NaviLink Gateway", namespace: "navien", author: "jlslate") {
        capability "Initialize"
        capability "Refresh"
        capability "Actuator"

        attribute "connection", "enum", ["connected", "connecting", "disconnected", "http-fallback"]
        attribute "channels", "number"
        attribute "lastMessage", "string"

        command "reconnect"
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
    initialize()
}

def updated() {
    logDebug "updated()"
    initialize()
}

def uninstalled() {
    disconnectMqtt()
}

def initialize() {
    unschedule()
    state.clientId = UUID.randomUUID().toString()
    state.channelNumbers = state.channelNumbers ?: []
    disconnectMqtt()
    runIn(2, "connect")
    // Watchdog: reconnect if the broker drops us or the credentials expire.
    runEvery5Minutes("healthCheck")
}

def reconnect() {
    logDebug "reconnect() requested"
    disconnectMqtt()
    runIn(2, "connect")
}

def credentialsRefreshed() {
    // Parent signed in again; the pre-signed URL is tied to those credentials,
    // so drop and rebuild the connection with the new ones.
    logDebug "Parent refreshed AWS credentials, reconnecting"
    reconnect()
}

// ===================================================================================
// Connection
// ===================================================================================

def connect() {
    Map creds
    try {
        creds = parent.getAwsCredentials()
    } catch (Exception e) {
        log.error "Could not get NaviLink credentials from the parent app: ${e.message}"
        sendEvent(name: "connection", value: "disconnected")
        runIn(120, "connect")
        return
    }

    if (!creds?.accessKeyId || !creds?.secretKey || !creds?.sessionToken) {
        log.error "NaviLink did not return AWS credentials; check the account in the parent app"
        sendEvent(name: "connection", value: "disconnected")
        runIn(300, "connect")
        return
    }

    state.endpoint = creds.endpoint
    state.region = creds.region
    state.userSeq = creds.userSeq?.toString()

    sendEvent(name: "connection", value: "connecting")
    String url = presignIotWebsocketUrl(creds)
    logDebug "Connecting to ${creds.endpoint} as ${state.clientId}"

    try {
        // Named arguments here on purpose: Hubitat's connect() takes the options map
        // as its first parameter, which is what Groovy builds from named arguments.
        interfaces.mqtt.connect(
            url,
            state.clientId,
            "?SDK=Android&Version=2.16.12",
            null,
            lastWillTopic  : topicAppConnection(),
            lastWillQos    : 1,
            lastWillMessage: JsonOutput.toJson(lastWillMessage()),
            cleanSession   : true
        )
    } catch (Exception e) {
        log.error "MQTT connect to the Navien cloud failed: ${e.message}"
        connectFailed()
        return
    }

    runIn(3, "onConnected")
}

/**
 * Called whenever a connection attempt does not produce a live MQTT session.
 * After a few tries the driver settles into HTTPS-only mode so that commands still
 * work, and keeps retrying MQTT in the background.
 */
private void connectFailed() {
    Integer failures = ((state.connectFailures ?: 0) as Integer) + 1
    state.connectFailures = failures
    if (failures >= 3) {
        if (device.currentValue("connection") != "http-fallback") {
            log.warn "Could not establish MQTT after ${failures} attempts. Commands will be sent over the " +
                     "AWS IoT HTTPS endpoint instead; live status is not available in that mode."
        }
        sendEvent(name: "connection", value: "http-fallback")
        runIn(900, "connect")
    } else {
        sendEvent(name: "connection", value: "disconnected")
        runIn(60, "connect")
    }
}

def onConnected() {
    if (!interfaces.mqtt.isConnected()) {
        log.warn "MQTT client is not connected after the connect attempt"
        connectFailed()
        return
    }

    state.connectFailures = 0
    sendEvent(name: "connection", value: "connected")
    if (txtEnable != false) log.info "${device.displayName} connected to the Navien cloud"

    subscribeTopics()
    runIn(2, "requestChannelInfo")

    Integer minutes = 5
    try { minutes = parent.getStatusPollMinutes() } catch (Exception ignored) { }
    switch (minutes) {
        case 1:  runEvery1Minute("refresh");   break
        case 10: runEvery10Minutes("refresh"); break
        case 15: runEvery15Minutes("refresh"); break
        case 30: runEvery30Minutes("refresh"); break
        default: runEvery5Minutes("refresh");  break
    }
}

private void subscribeTopics() {
    [
        topicChannelInfoSub(),
        topicChannelInfoRes(),
        topicChannelStatusSub(),
        topicChannelStatusRes(),
        topicControlFail(),
        topicConnection()
    ].each { String t ->
        try {
            interfaces.mqtt.subscribe(t, 1)
            logDebug "Subscribed to ${t}"
        } catch (Exception e) {
            log.warn "Could not subscribe to ${t}: ${e.message}"
        }
    }
}

private void disconnectMqtt() {
    try {
        if (interfaces.mqtt.isConnected()) interfaces.mqtt.disconnect()
    } catch (Exception ignored) { }
    sendEvent(name: "connection", value: "disconnected")
}

def healthCheck() {
    if (device.currentValue("connection") == "http-fallback") return
    if (!interfaces.mqtt.isConnected()) {
        log.warn "MQTT connection to the Navien cloud is down; reconnecting"
        connect()
    }
}

def mqttClientStatus(String message) {
    logDebug "mqttClientStatus: ${message}"
    if (message?.startsWith("Error")) {
        log.warn "MQTT error: ${message}"
        sendEvent(name: "connection", value: "disconnected")
        runIn(30, "connect")
    } else if (message?.contains("Connection succeeded")) {
        sendEvent(name: "connection", value: "connected")
    }
}

// ===================================================================================
// Incoming messages
// ===================================================================================

def parse(String description) {
    Map msg
    try {
        msg = interfaces.mqtt.parseMessage(description)
    } catch (Exception e) {
        log.warn "Could not parse MQTT frame: ${e.message}"
        return
    }

    String topic = msg?.topic ?: ""
    String payload = msg?.payload ?: ""
    logDebug "RX ${topic}: ${payload.take(500)}"
    sendEvent(name: "lastMessage", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))

    def json
    try {
        json = new JsonSlurper().parseText(payload)
    } catch (Exception e) {
        log.warn "Non-JSON payload on ${topic}"
        return
    }

    if (topic.endsWith("channelinfo")) {
        handleChannelInfo(json)
    } else if (topic.endsWith("channelstatus")) {
        handleChannelStatus(json)
    } else if (topic.endsWith("controlfail")) {
        log.warn "Navien rejected a control command: ${payload.take(300)}"
    } else {
        logDebug "Unhandled topic ${topic}"
    }
}

private void handleChannelInfo(json) {
    def channelList = json?.response?.channelInfo?.channelList
    if (!(channelList instanceof List)) {
        logDebug "channelinfo without a channelList; ignoring"
        return
    }

    // Record the channel numbers before creating any devices, so a multi-channel
    // gateway labels its children "... Channel N" on the first pass too.
    List numbers = channelList.collect { entry ->
        Map ch = (entry.channel ?: entry) as Map
        ((ch.channelNumber ?: entry.channelNumber ?: 1) as Integer)
    }
    state.channelNumbers = numbers
    state.channelUnitCounts = channelList.collectEntries { entry ->
        Map ch = (entry.channel ?: entry) as Map
        [((ch.channelNumber ?: 1) as Integer).toString(), (ch.unitCount ?: 1) as Integer]
    }

    channelList.each { entry ->
        Map ch = (entry.channel ?: entry) as Map
        Integer number = (ch.channelNumber ?: entry.channelNumber ?: 1) as Integer
        Integer tempType = (ch.temperatureType ?: 2) as Integer
        Integer unitCount = (ch.unitCount ?: 1) as Integer

        def child = createChannelDevice(number)
        if (child) {
            child.updateDataValue("channelNumber", number.toString())
            child.updateDataValue("unitCount", unitCount.toString())
            child.updateDataValue("temperatureType", tempType.toString())
            child.updateDataValue("onDemandUse", ((ch.onDemandUse ?: 2) as Integer).toString())
            child.setLimits(decodeTemp(ch.setupDHWTempMin, tempType), decodeTemp(ch.setupDHWTempMax, tempType), tempType)
        }
    }

    sendEvent(name: "channels", value: numbers.size())
    if (txtEnable != false) log.info "${device.displayName} found channel(s) ${numbers.join(', ')}"
    runIn(2, "refresh")
}

private void handleChannelStatus(json) {
    def status = json?.response?.channelStatus
    if (!status) {
        logDebug "channelstatus without a channelStatus body; ignoring"
        return
    }
    Integer number = (status.channelNumber ?: 1) as Integer
    Map ch = (status.channel ?: status) as Map
    def child = getChildDevice(channelDni(number))
    if (!child) {
        child = createChannelDevice(number)
    }
    if (child) {
        child.updateStatus(ch)
    }
}

// ===================================================================================
// Outgoing requests and commands
// ===================================================================================

def refresh() {
    List numbers = (state.channelNumbers ?: [1])
    numbers.each { requestChannelStatus(it as Integer) }
}

def requestChannelInfo() {
    Map payload = [
        clientID       : state.clientId,
        protocolVersion: 1,
        request        : [
            additionalValue: additionalValue(),
            command        : CMD_CHANNEL_INFO,
            deviceType     : deviceType(),
            macAddress     : macAddress()
        ],
        requestTopic   : topicStart(),
        responseTopic  : topicChannelInfoRes(),
        sessionID      : now().toString()
    ]
    publishMessage(topicStart(), payload)
}

def requestChannelStatus(Integer channelNumber) {
    Integer unitCount = ((state.channelUnitCounts ?: [:])[channelNumber.toString()] ?: 1) as Integer
    Map payload = [
        clientID       : state.clientId,
        protocolVersion: 1,
        request        : [
            additionalValue: additionalValue(),
            command        : CMD_CHANNEL_STATUS,
            deviceType     : deviceType(),
            macAddress     : macAddress(),
            status         : [channelNumber: channelNumber, unitNumberStart: 1, unitNumberEnd: unitCount]
        ],
        requestTopic   : topicChannelStatusReq(),
        responseTopic  : topicChannelStatusRes(),
        sessionID      : now().toString()
    ]
    publishMessage(topicChannelStatusReq(), payload)
}

// Called by channel children
void setPower(Integer channelNumber, Boolean on) {
    sendControl(channelNumber, CMD_POWER, "power", [on ? 1 : 2])
}

void setOnDemand(Integer channelNumber, Boolean on) {
    sendControl(channelNumber, CMD_ON_DEMAND, "onDemand", [on ? 1 : 2])
}

void setTemperature(Integer channelNumber, Integer rawTemp) {
    sendControl(channelNumber, CMD_TEMPERATURE, "DHWTemperature", [rawTemp])
}

void refreshChannel(Integer channelNumber) {
    requestChannelStatus(channelNumber)
}

private void sendControl(Integer channelNumber, Integer command, String mode, List params) {
    Map payload = [
        clientID       : state.clientId,
        protocolVersion: 1,
        request        : [
            additionalValue: additionalValue(),
            command        : command,
            control        : [channelNumber: channelNumber, mode: mode, param: params],
            deviceType     : deviceType(),
            macAddress     : macAddress()
        ],
        requestTopic   : topicControl(),
        responseTopic  : topicChannelStatusRes(),
        sessionID      : now().toString()
    ]
    publishMessage(topicControl(), payload)
    // Navien answers a control with a status frame, but ask again so the device
    // settles on the real value rather than an optimistic one.
    runIn(5, "refresh")
}

private void publishMessage(String topic, Map payload) {
    String body = JsonOutput.toJson(payload)
    logDebug "TX ${topic}: ${body.take(500)}"
    if (interfaces.mqtt.isConnected()) {
        try {
            interfaces.mqtt.publish(topic, body, 1, false)
            return
        } catch (Exception e) {
            log.warn "MQTT publish failed (${e.message}); trying the HTTPS endpoint"
        }
    }
    httpPublish(topic, body)
}

/**
 * AWS IoT HTTPS publish endpoint. Only used when the MQTT client is unavailable.
 * Commands get through; responses do not, since HTTPS publish cannot subscribe.
 */
private void httpPublish(String topic, String body) {
    Map creds
    try {
        creds = parent.getAwsCredentials()
    } catch (Exception e) {
        log.error "No credentials available for the HTTPS publish fallback: ${e.message}"
        return
    }

    String host = "${creds.endpoint}:8443"
    String path = "/topics/${topic}"
    String query = "qos=1"
    Map headers = sigV4Headers("POST", path, query, body, creds, host)

    try {
        httpPost([
            uri                 : "https://${host}",
            path                : path,
            query               : [qos: 1],
            headers             : headers,
            requestContentType  : "application/json",
            contentType         : "application/json",
            body                : body,
            timeout             : 20
        ]) { resp ->
            logDebug "HTTPS publish returned ${resp.status}"
        }
    } catch (Exception e) {
        log.error "HTTPS publish to ${topic} failed: ${e.message}"
    }
}

// ===================================================================================
// Child channel devices
// ===================================================================================

private String channelDni(Integer number) {
    return "${device.deviceNetworkId}-ch${number}"
}

private createChannelDevice(Integer number) {
    String dni = channelDni(number)
    def child = getChildDevice(dni)
    if (child) return child
    try {
        String base = getDataValue("deviceName") ?: device.displayName
        String label = (state.channelNumbers?.size() ?: 1) > 1 ? "${base} Channel ${number}" : base as String
        child = addChildDevice("navien", "Navien NaviLink Water Heater", dni,
                               [name: "Navien NaviLink Water Heater", label: label, isComponent: false])
        log.info "Created channel device ${label} (${dni})"
    } catch (Exception e) {
        log.error "Could not create the channel ${number} device: ${e.message}"
        return null
    }
    return child
}

// ===================================================================================
// Topics and messages
// ===================================================================================

private String macAddress()      { return getDataValue("macAddress") ?: "" }
private Integer deviceType()     { return (getDataValue("deviceType") ?: "1") as Integer }
private String additionalValue() { return getDataValue("additionalValue") ?: "" }
private String homeSeq()         { return getDataValue("homeSeq") ?: "" }

private String reqPrefix() { return "cmd/${deviceType()}/navilink-${macAddress()}/" }
private String resPrefix() { return "cmd/${deviceType()}/${homeSeq()}/${state.userSeq}/${state.clientId}/res/" }

private String topicStart()             { return reqPrefix() + "status/start" }
private String topicChannelInfoSub()    { return reqPrefix() + "res/channelinfo" }
private String topicChannelInfoRes()    { return resPrefix() + "channelinfo" }
private String topicChannelStatusSub()  { return reqPrefix() + "res/channelstatus" }
private String topicChannelStatusReq()  { return reqPrefix() + "status/channelstatus" }
private String topicChannelStatusRes()  { return resPrefix() + "channelstatus" }
private String topicControl()           { return reqPrefix() + "control" }
private String topicControlFail()       { return reqPrefix() + "res/controlfail" }
private String topicConnection()        { return reqPrefix() + "connection" }
private String topicAppConnection()     { return "evt/1/navilink-${macAddress()}/app-connection" }

private Map lastWillMessage() {
    return [
        clientID       : state.clientId,
        event          : [
            additionalValue: additionalValue(),
            connection     : [os: "A", status: 0],
            deviceType     : deviceType(),
            macAddress     : macAddress()
        ],
        protocolVersion: 1,
        requestTopic   : topicAppConnection(),
        sessionID      : ""
    ]
}

// ===================================================================================
// AWS Signature Version 4
// ===================================================================================

private String presignIotWebsocketUrl(Map creds) {
    String service = "iotdevicegateway"
    String host = creds.endpoint
    String region = creds.region
    Date nowDate = new Date()
    String amzDate = utcFormat("yyyyMMdd'T'HHmmss'Z'", nowDate)
    String dateStamp = utcFormat("yyyyMMdd", nowDate)
    String scope = "${dateStamp}/${region}/${service}/aws4_request"

    // AWS IoT expects the security token appended *after* signing, so it is not
    // part of the canonical query string.
    String canonicalQuery = [
        "X-Amz-Algorithm=AWS4-HMAC-SHA256",
        "X-Amz-Credential=" + uriEncode("${creds.accessKeyId}/${scope}"),
        "X-Amz-Date=${amzDate}",
        "X-Amz-Expires=86400",
        "X-Amz-SignedHeaders=host"
    ].join("&")

    String canonicalRequest = [
        "GET",
        "/mqtt",
        canonicalQuery,
        "host:${host}",
        "",
        "host",
        sha256Hex("")
    ].join("\n")

    String stringToSign = [
        "AWS4-HMAC-SHA256",
        amzDate,
        scope,
        sha256Hex(canonicalRequest)
    ].join("\n")

    byte[] signingKey = signatureKey(creds.secretKey as String, dateStamp, region, service)
    String signature = toHex(hmacSha256(signingKey, stringToSign))

    return "wss://${host}:443/mqtt?${canonicalQuery}&X-Amz-Signature=${signature}" +
           "&X-Amz-Security-Token=" + uriEncode(creds.sessionToken as String)
}

private Map sigV4Headers(String method, String path, String query, String body, Map creds, String host) {
    String service = "iotdevicegateway"
    String region = creds.region
    Date nowDate = new Date()
    String amzDate = utcFormat("yyyyMMdd'T'HHmmss'Z'", nowDate)
    String dateStamp = utcFormat("yyyyMMdd", nowDate)
    String scope = "${dateStamp}/${region}/${service}/aws4_request"
    String payloadHash = sha256Hex(body)
    String signedHeaders = "host;x-amz-date;x-amz-security-token"

    String canonicalRequest = [
        method,
        path,
        query,
        "host:${host}",
        "x-amz-date:${amzDate}",
        "x-amz-security-token:${creds.sessionToken}",
        "",
        signedHeaders,
        payloadHash
    ].join("\n")

    String stringToSign = [
        "AWS4-HMAC-SHA256",
        amzDate,
        scope,
        sha256Hex(canonicalRequest)
    ].join("\n")

    byte[] signingKey = signatureKey(creds.secretKey as String, dateStamp, region, service)
    String signature = toHex(hmacSha256(signingKey, stringToSign))

    return [
        "Host"                : host,
        "X-Amz-Date"          : amzDate,
        "X-Amz-Security-Token": creds.sessionToken as String,
        "Authorization"       : "AWS4-HMAC-SHA256 Credential=${creds.accessKeyId}/${scope}, " +
                                "SignedHeaders=${signedHeaders}, Signature=${signature}"
    ]
}

private byte[] signatureKey(String secret, String dateStamp, String region, String service) {
    byte[] kDate    = hmacSha256(("AWS4" + secret).getBytes("UTF-8"), dateStamp)
    byte[] kRegion  = hmacSha256(kDate, region)
    byte[] kService = hmacSha256(kRegion, service)
    return hmacSha256(kService, "aws4_request")
}

private byte[] hmacSha256(byte[] key, String data) {
    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.getBytes("UTF-8"))
}

private String sha256Hex(String data) {
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256")
    return toHex(md.digest(data.getBytes("UTF-8")))
}

private String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder()
    bytes.each { b -> sb.append(String.format("%02x", b)) }
    return sb.toString()
}

private String uriEncode(String value) {
    return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
}

private String utcFormat(String pattern, Date date) {
    SimpleDateFormat sdf = new SimpleDateFormat(pattern)
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
    return sdf.format(date)
}

// ===================================================================================
// Helpers
// ===================================================================================

private BigDecimal decodeTemp(value, Integer temperatureType) {
    if (value == null) return null
    BigDecimal v = value as BigDecimal
    // Celsius devices report the setpoint in half-degree units; Fahrenheit ones are whole degrees.
    return (temperatureType == 1) ? (v / 2.0).setScale(1, BigDecimal.ROUND_HALF_UP) : v
}

private void logDebug(String msg) {
    if (logEnable != false) log.debug msg
}
