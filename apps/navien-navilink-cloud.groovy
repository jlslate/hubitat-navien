import groovy.transform.Field

/**
 *  Navien NaviLink (Cloud)
 *
 *  Hubitat app that connects to the Navien NaviLink cloud service, enumerates the
 *  NaviLink gateways on the account and creates a child device for each one.
 *
 *  The NaviLink cloud is two pieces:
 *    1. a small REST API (sign-in + device list) that also hands back temporary
 *       AWS IAM credentials, and
 *    2. an AWS IoT Core MQTT broker that carries all live status and control.
 *
 *  This app owns piece 1. The gateway driver owns piece 2, because Hubitat only
 *  allows an MQTT client inside a driver.
 *
 *  Licensed under the Apache License, Version 2.0
 */

definition(
    name: "Navien NaviLink (Cloud)",
    namespace: "jlslate",
    author: "jlslate",
    description: "Cloud connection for Navien tankless water heaters and combi boilers via NaviLink.",
    category: "Green Living",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "discoveryPage")
}

@Field static final String NAVIEN_API = "https://nlus.naviensmartcontrol.com/api/v2"
@Field static final Integer CREDENTIAL_MAX_AGE_MS = 40 * 60 * 1000

// ===================================================================================
// Pages
// ===================================================================================

def mainPage() {
    dynamicPage(name: "mainPage", title: "<h2>Navien NaviLink (Cloud)</h2>", install: true, uninstall: true) {
        section("<b>NaviLink account</b>") {
            input name: "navienUser", type: "text", title: "NaviLink email address", required: true, submitOnChange: true
            input name: "navienPassword", type: "password", title: "NaviLink password", required: true, submitOnChange: true
            paragraph "Use the same credentials as the NaviLink mobile app. Signing in here does not sign the app out."
        }

        if (navienUser && navienPassword) {
            section("<b>Devices</b>") {
                href name: "toDiscovery", page: "discoveryPage", title: "Discover NaviLink devices",
                     description: "Sign in and create/refresh a Hubitat device for each NaviLink gateway"
                List children = getChildDevices()
                if (children) {
                    paragraph "<b>Installed:</b><br>" + children.collect { "&bull; ${it.displayName}" }.join("<br>")
                } else {
                    paragraph "<i>No devices installed yet.</i>"
                }
            }
        }

        section("<b>Options</b>") {
            input name: "pollInterval", type: "enum", title: "Status polling interval (minutes)",
                  options: ["1": "1", "5": "5", "10": "10", "15": "15", "30": "30"], defaultValue: "5", required: true
            input name: "iotEndpoint", type: "text", title: "AWS IoT endpoint",
                  defaultValue: "a1t30mldyslmuq-ats.iot.us-east-1.amazonaws.com", required: true
            input name: "iotRegion", type: "text", title: "AWS region", defaultValue: "us-east-1", required: true
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        }

        section {
            label title: "Name this app instance", required: false
        }
    }
}

def discoveryPage() {
    dynamicPage(name: "discoveryPage", title: "<h2>NaviLink discovery</h2>", install: false, uninstall: false) {
        Map result = [:]
        try {
            result = signIn()
        } catch (Exception e) {
            log.error "NaviLink sign-in failed: ${e.message}"
            section { paragraph "<b>Sign-in failed:</b> ${e.message}" }
            return
        }

        if (!result) {
            section { paragraph "<b>Sign-in failed.</b> Check the email address and password and try again." }
            return
        }

        List devices = []
        try {
            devices = fetchDeviceList()
        } catch (Exception e) {
            log.error "NaviLink device list failed: ${e.message}"
            section { paragraph "<b>Could not retrieve the device list:</b> ${e.message}" }
            return
        }

        if (!devices) {
            section { paragraph "Signed in successfully, but the account has no NaviLink devices on it." }
            return
        }

        List<String> added = []
        state.discoveredDevices = devices.collect { Map entry -> (entry.deviceInfo ?: entry) as Map }
        state.discoveredDevices.each { Map info ->
            def dev = createGatewayDevice(info)
            added << (dev ? "${dev.displayName} (${info.macAddress})"
                          : "${info.deviceName ?: info.macAddress} - will be created when you press Done")
        }

        section {
            paragraph "<b>Found ${devices.size()} NaviLink device(s):</b><br>" + added.collect { "&bull; ${it}" }.join("<br>")
            paragraph "Each gateway opens its own MQTT connection to the Navien cloud and creates one child device " +
                      "per heating channel it reports. Give it up to a minute after install."
            href name: "backToMain", page: "mainPage", title: "Done", description: "Return to the main page"
        }
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
    unsubscribe()
    unschedule()
    initialize()
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    state.remove("credentials")
    state.remove("credentialsAt")
    // Temporary AWS credentials are short lived; refresh well inside their lifetime
    // and hand the fresh set to every gateway child so it can re-establish MQTT.
    runEvery30Minutes("refreshCredentials")
    if (navienUser && navienPassword) {
        try {
            signIn()
        } catch (Exception e) {
            log.warn "NaviLink sign-in during initialize failed: ${e.message}"
        }
    }
    (state.discoveredDevices ?: []).each { info ->
        createGatewayDevice(info as Map)
    }
    getChildDevices().each { child ->
        try { child.initialize() } catch (Exception e) { log.warn "Could not initialize ${child.displayName}: ${e.message}" }
    }
}

// ===================================================================================
// NaviLink REST API
// ===================================================================================

private Map signIn() {
    Map body = [userId: navienUser, password: navienPassword]
    // The full URL goes in uri: Hubitat replaces the URI's path with the `path`
    // parameter rather than appending to it, which would drop the /api/v2 prefix
    // and earn a 403 "Missing Authentication Token" from Navien's API gateway.
    Map params = [
        uri: "${NAVIEN_API}/user/sign-in",
        contentType: "application/json",
        requestContentType: "application/json",
        body: body,
        timeout: 30
    ]

    Map userInfo = null
    httpPost(params) { resp ->
        if (resp.status != 200) throw new Exception("HTTP ${resp.status} from sign-in")
        def data = resp.data instanceof String ? parseJson(resp.data) : resp.data
        // Navien answers a rejected sign-in with HTTP 200 and an error in the body.
        if (!data?.data) throw new Exception(describeApiError(data, "sign-in"))
        userInfo = data.data as Map
    }

    if (!userInfo?.token?.accessToken) throw new Exception("Sign-in response contained no access token")

    state.accessToken = userInfo.token.accessToken
    state.userSeq = userInfo.userInfo?.userSeq
    state.credentials = [
        accessKeyId : userInfo.token.accessKeyId,
        secretKey   : userInfo.token.secretKey,
        sessionToken: userInfo.token.sessionToken
    ]
    state.credentialsAt = now()
    logDebug "Signed in to NaviLink (userSeq=${state.userSeq})"
    return userInfo
}

private List fetchDeviceList() {
    Map params = [
        uri: "${NAVIEN_API}/device/list",
        headers: ["Authorization": state.accessToken],
        contentType: "application/json",
        requestContentType: "application/json",
        body: [offset: 0, count: 20, userId: navienUser],
        timeout: 30
    ]

    List devices = []
    httpPost(params) { resp ->
        if (resp.status != 200) throw new Exception("HTTP ${resp.status} from device list")
        def data = resp.data instanceof String ? parseJson(resp.data) : resp.data
        def payload = data?.data
        if (payload == null) throw new Exception(describeApiError(data, "device list"))
        if (payload instanceof List) {
            devices = payload
        } else if (payload instanceof Map) {
            // Defensive: some accounts return the list wrapped in an object
            devices = (payload.deviceList ?: payload.devices ?: []) as List
        }
    }
    state.deviceCount = devices.size()
    logDebug "NaviLink returned ${devices.size()} device(s)"
    return devices
}

/**
 * Navien returns HTTP 200 with {"code": ..., "msg": ...} for failures such as a bad
 * password, so the body is where the real error lives.
 */
private String describeApiError(data, String what) {
    String msg = data?.msg
    def code = data?.code
    if (msg) {
        String hint = ""
        if (msg.toString().contains("USER_NOT_FOUND")) hint = " - check the email address"
        else if (msg.toString().toUpperCase().contains("PASSWORD")) hint = " - check the password"
        String codePart = code ? ' (code ' + code + ')' : ''
        return "NaviLink rejected the ${what}: ${msg}${codePart}${hint}"
    }
    return "Unexpected ${what} response from NaviLink: ${data}"
}

// ===================================================================================
// Child device management
// ===================================================================================

private createGatewayDevice(Map info) {
    String mac = info?.macAddress
    if (!mac) {
        log.warn "NaviLink device with no MAC address skipped: ${info}"
        return null
    }
    String dni = "navien-${mac}"
    def child = getChildDevice(dni)
    if (!child) {
        String label = info.deviceName ?: "Navien NaviLink"
        try {
            child = addChildDevice("jlslate", "Navien NaviLink Gateway", dni, [name: "Navien NaviLink Gateway", label: label])
            log.info "Created NaviLink gateway device ${label} (${dni})"
        } catch (Exception e) {
            // Happens when discovery runs before this app instance has been saved.
            // initialize() retries from state.discoveredDevices once the app is installed.
            log.warn "Could not create the device for ${mac} yet (${e.message}); it will be created on install"
            return null
        }
    }
    child.updateDataValue("macAddress", mac.toString())
    child.updateDataValue("homeSeq", (info.homeSeq ?: "").toString())
    child.updateDataValue("deviceType", (info.deviceType ?: 1).toString())
    child.updateDataValue("additionalValue", (info.additionalValue ?: "").toString())
    child.updateDataValue("deviceName", (info.deviceName ?: "").toString())
    try { child.initialize() } catch (Exception e) { log.warn "Could not initialize ${child.displayName}: ${e.message}" }
    return child
}

// ===================================================================================
// Called by child devices
// ===================================================================================

Map getAwsCredentials(Boolean force = false) {
    Long age = state.credentialsAt ? (now() - (state.credentialsAt as Long)) : Long.MAX_VALUE
    if (force || !state.credentials?.accessKeyId || age > CREDENTIAL_MAX_AGE_MS) {
        signIn()
    }
    return [
        accessKeyId : state.credentials?.accessKeyId,
        secretKey   : state.credentials?.secretKey,
        sessionToken: state.credentials?.sessionToken,
        userSeq     : state.userSeq,
        endpoint    : iotEndpoint ?: "a1t30mldyslmuq-ats.iot.us-east-1.amazonaws.com",
        region      : iotRegion ?: "us-east-1"
    ]
}

Integer getStatusPollMinutes() {
    return (pollInterval ?: "5") as Integer
}

def refreshCredentials() {
    try {
        signIn()
        getChildDevices().each { child ->
            try { child.credentialsRefreshed() } catch (Exception e) { log.warn "Could not push credentials to ${child.displayName}: ${e.message}" }
        }
    } catch (Exception e) {
        log.warn "Scheduled NaviLink credential refresh failed: ${e.message}"
    }
}

private void logDebug(String msg) {
    if (logEnable != false) log.debug msg
}
