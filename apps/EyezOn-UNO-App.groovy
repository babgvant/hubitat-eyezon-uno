/**
 *  EyezOn UNO Integration
 *
 *  Connects directly to an EyezOn UNO over its local TPI socket (no cloud, no proxy
 *  service) and creates child devices for each zone and partition. Requires the
 *  "EyezOn UNO Connection" and "EyezOn UNO Partition" drivers.
 */
definition(
    name: "EyezOn UNO Integration",
    namespace: "eyezonUno",
    author: "babgvant",
    description: "Direct local integration with an EyezOn UNO alarm panel",
    category: "Safety & Security",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section("UNO Connection") {
            input "ip", "text", title: "UNO IP Address", required: true
            input "port", "number", title: "UNO Port", defaultValue: 4025, required: true
            input "password", "password", title: "UNO TPI Password", required: true
            input "masterCode", "password", title: "Master/access code (for disarm and code-required prompts)", required: false
        }

        section("Partitions and Zones") {
            input "partitionCount", "number", title: "Number of partitions", defaultValue: 1, required: true
            input "zoneCount", "number", title: "Number of zones", defaultValue: 8, required: true
            input "defaultUnknownZoneType", "enum",
                title: "Default type for zones without a hint",
                options: ["contact": "Contact", "motion": "Motion", "smoke": "Smoke", "co": "CO", "water": "Water"],
                defaultValue: "contact", required: true
        }

        section("Zone Hints (optional)") {
            paragraph "One zone per line: zoneNumber:Label:type. Example:\n1:Front Door:contact\n2:Hallway PIR:motion\n5:Basement Smoke:smoke"
            input "zoneHints", "textarea", title: "Zone Hints", required: false
        }

        section("Logging / Health") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
            input "traceLogging", "bool", title: "Enable trace (raw frame) logging", defaultValue: false
            input "heartbeatMinutes", "enum", title: "Poll interval",
                options: ["10s": "Every 10 seconds", "20s": "Every 20 seconds", "30s": "Every 30 seconds", "1": "Every 1 minute", "3": "Every 3 minutes", "5": "Every 5 minutes"],
                defaultValue: "1", required: true
            input "watchdogMinutes", "enum", title: "Reconnect if no frames seen for",
                options: ["3": "3 minutes", "5": "5 minutes", "10": "10 minutes"],
                defaultValue: "5", required: true
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    ensureConnectionDevice()
    ensurePartitionDevices()
    ensureZoneDevices()
    applyConnectionSettings()
    runIn(1, "initializeConnection")
}

def initializeConnection() {
    getChildDevice("uno-connection")?.initialize()
}

/* ---------------- Child device management ---------------- */

private void ensureConnectionDevice() {
    String dni = "uno-connection"
    if (!getChildDevice(dni)) {
        addChildDevice("eyezonUno", "EyezOn UNO Connection", dni,
            [name: "UNO Connection", label: "UNO Connection", isComponent: false])
        logInfo("Created UNO Connection device")
    }
}

private void ensurePartitionDevices() {
    Integer count = safeInt(settings.partitionCount, 1)
    (1..count).each { Integer p ->
        String dni = "uno-partition-${p}"
        if (!getChildDevice(dni)) {
            String label = count > 1 ? "House Alarm Partition ${p}" : "House Alarm"
            addChildDevice("eyezonUno", "EyezOn UNO Partition", dni,
                [name: "UNO Partition ${p}", label: label, isComponent: true])
            logInfo("Created partition device ${p}")
        }
    }
}

private void ensureZoneDevices() {
    Map hints = parseZoneHints(settings.zoneHints)
    Integer count = safeInt(settings.zoneCount, 8)

    (1..count).each { Integer zoneNum ->
        Map hint = hints["${zoneNum}"] ?: [:]
        String type = canonicalType(hint.type ?: settings.defaultUnknownZoneType ?: "contact")
        String label = hint.label ?: "Zone ${zoneNum}"
        String dni = "uno-zone-${zoneNum}"

        def existing = getChildDevice(dni)
        String desiredDriver = driverNameForType(type)

        if (!existing) {
            addChildDevice("eyezonUno", desiredDriver, dni,
                [name: "UNO Zone ${zoneNum}", label: label, isComponent: true])
            logDebug("Created zone ${zoneNum} (${label}, ${desiredDriver})")
            return
        }

        String existingTypeName = null
        try { existingTypeName = existing.getTypeName() } catch (ignored) { }

        if (existingTypeName && existingTypeName != desiredDriver) {
            try {
                deleteChildDevice(dni)
                addChildDevice("eyezonUno", desiredDriver, dni,
                    [name: "UNO Zone ${zoneNum}", label: label, isComponent: true])
                logInfo("Recreated zone ${zoneNum} as ${desiredDriver}")
            } catch (e) {
                log.warn "EyezOn UNO: could not recreate zone ${zoneNum}: ${e}"
            }
        } else if (existing.label != label) {
            existing.setLabel(label)
        }
    }
}

private void applyConnectionSettings() {
    def conn = getChildDevice("uno-connection")
    if (!conn) return

    conn.updateSetting("ip", [value: settings.ip, type: "text"])
    conn.updateSetting("port", [value: "${settings.port ?: 4025}", type: "number"])
    conn.updateSetting("password", [value: settings.password, type: "password"])
    conn.updateSetting("masterCode", [value: settings.masterCode ?: "", type: "password"])
    conn.updateSetting("debugLogging", [value: settings.debugLogging == true, type: "bool"])
    conn.updateSetting("traceLogging", [value: settings.traceLogging == true, type: "bool"])
    conn.updateSetting("heartbeatMinutes", [value: (settings.heartbeatMinutes ?: "1").toString(), type: "enum"])
    conn.updateSetting("watchdogMinutes", [value: (settings.watchdogMinutes ?: "5").toString(), type: "enum"])
}

/* ---------------- Callbacks from the connection driver ---------------- */

def zoneStateChanged(Integer zoneNum, boolean active) {
    def child = getChildDevice("uno-zone-${zoneNum}")
    if (!child) return

    String typeName = null
    try { typeName = child.getTypeName() } catch (ignored) { }

    switch (typeName) {
        case "EyezOn UNO Zone Motion":
            child.parse([[name: "motion", value: active ? "active" : "inactive", descriptionText: "Zone ${zoneNum} motion ${active ? 'active' : 'inactive'}"]])
            break
        case "EyezOn UNO Zone Smoke":
            child.parse([[name: "smoke", value: active ? "detected" : "clear", descriptionText: "Zone ${zoneNum} smoke ${active ? 'detected' : 'clear'}"]])
            break
        case "EyezOn UNO Zone CO":
            child.parse([[name: "carbonMonoxide", value: active ? "detected" : "clear", descriptionText: "Zone ${zoneNum} CO ${active ? 'detected' : 'clear'}"]])
            break
        case "EyezOn UNO Zone Water":
            child.parse([[name: "water", value: active ? "wet" : "dry", descriptionText: "Zone ${zoneNum} water ${active ? 'wet' : 'dry'}"]])
            break
        default:
            child.parse([[name: "contact", value: active ? "open" : "closed", descriptionText: "Zone ${zoneNum} ${active ? 'open' : 'closed'}"]])
            break
    }
}

def partitionStateChanged(Integer partitionNum, String status) {
    getChildDevice("uno-partition-${partitionNum}")?.setStatus(status)
}

def partitionTroubleChanged(Integer partitionNum, boolean active) {
    getChildDevice("uno-partition-${partitionNum}")?.setTrouble(active)
}

/* ---------------- Commands proxied from partition devices ---------------- */

def refreshAll() {
    getChildDevice("uno-connection")?.refresh()
}

def armAway(Integer partitionNum) {
    getChildDevice("uno-connection")?.armAway(partitionNum)
}

def armStay(Integer partitionNum) {
    getChildDevice("uno-connection")?.armStay(partitionNum)
}

def disarm(Integer partitionNum, String code) {
    getChildDevice("uno-connection")?.disarm(partitionNum, code)
}

/* ---------------- Helpers ---------------- */

private String driverNameForType(String type) {
    switch (type) {
        case "motion": return "EyezOn UNO Zone Motion"
        case "smoke": return "EyezOn UNO Zone Smoke"
        case "co": return "EyezOn UNO Zone CO"
        case "water": return "EyezOn UNO Zone Water"
        default: return "EyezOn UNO Zone Contact"
    }
}

private String canonicalType(String raw) {
    String t = (raw ?: "contact").toLowerCase().trim()
    switch (t) {
        case "pir": return "motion"
        case "fire": case "heat": return "smoke"
        case "carbonmonoxide": case "carbon_monoxide": return "co"
        case "leak": case "flood": return "water"
        default: return t
    }
}

private Map parseZoneHints(String raw) {
    Map result = [:]
    if (!raw) return result

    raw.split("\n").each { String line ->
        String cleaned = line?.trim()
        if (!cleaned || cleaned.startsWith("#")) return

        List<String> parts = cleaned.split(":") as List<String>
        if (parts.size() < 2) return

        String zone = parts[0]?.trim()
        if (!zone?.isInteger()) return

        result[zone] = [
            label: parts[1]?.trim(),
            type : parts.size() >= 3 ? parts[2]?.trim() : null
        ]
    }
    return result
}

private Integer safeInt(def val, Integer fallback) {
    try {
        return (val == null) ? fallback : Integer.parseInt(val.toString())
    } catch (ignored) {
        return fallback
    }
}

private void logInfo(String msg) { log.info "EyezOn UNO: ${msg}" }
private void logWarn(String msg) { log.warn "EyezOn UNO: ${msg}" }
private void logDebug(String msg) { if (settings.debugLogging) log.debug "EyezOn UNO: ${msg}" }
