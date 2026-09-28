/**
 *  EyezOn UNO Connection Driver
 *
 *  Talks to the UNO TPI socket (port 4025). The UNO protocol uses a plain-text
 *  login followed by %CC,DATA$ reports and ^CC,DATA$ commands; it is distinct
 *  from the DSC EnvisaLink TPI protocol.
 */
metadata {
    definition(name: "EyezOn UNO Connection", namespace: "eyezonUno", author: "babgvant") {
        capability "Initialize"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        attribute "connectionState", "string"
        attribute "lastAck", "string"
        attribute "lastError", "string"
        attribute "lastFrame", "string"
        attribute "batteryTrouble", "string"
        attribute "acTrouble", "string"
        attribute "bellTrouble", "string"
        attribute "ftcTrouble", "string"
        attribute "systemTamper", "string"

        command "armAway", [[name: "Partition", type: "NUMBER"]]
        command "armStay", [[name: "Partition", type: "NUMBER"]]
        command "disarm", [[name: "Partition", type: "NUMBER"], [name: "Code", type: "STRING"]]
        command "poll"
        command "statusReport"
        command "reconnect"
    }

    preferences {
        input name: "ip", type: "text", title: "UNO IP Address", required: true
        input name: "port", type: "number", title: "UNO Port", defaultValue: 4025, required: true
        input name: "password", type: "password", title: "UNO TPI Password", required: true
        input name: "masterCode", type: "password", title: "Master code (for auto code-request replies)", required: false
        input name: "heartbeatMinutes", type: "enum", title: "Poll interval",
            options: ["10s": "Every 10 seconds", "20s": "Every 20 seconds", "30s": "Every 30 seconds", "1": "Every 1 minute", "3": "Every 3 minutes", "5": "Every 5 minutes"],
            defaultValue: "1", required: true
        input name: "watchdogMinutes", type: "enum", title: "Reconnect if no frames seen for",
            options: ["3": "3 minutes", "5": "5 minutes", "10": "10 minutes"],
            defaultValue: "5", required: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "traceLogging", type: "bool", title: "Enable trace (raw frame) logging", defaultValue: false
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    initialize()
}

def initialize() {
    state.rxBuffer = ""
    state.commandQueue = []
    state.commandInFlight = null
    state.loggedIn = false
    state.lastFrameTs = 0L
    state.lastFrameEventTs = 0L
    state.zoneBits = null
    state.partitionBytes = null
    state.troubleBytes = null

    sendEvent(name: "connectionState", value: "disconnected")

    scheduleHealthChecks()
    reconnect()
}

private void scheduleHealthChecks() {
    String hb = (settings.heartbeatMinutes ?: "1").toString()
    if (hb in ["10s", "20s", "30s"]) {
        Integer seconds = hb.replace("s", "").toInteger()
        schedule("0/${seconds} * * * * ?", "heartbeatCheck")
    } else if (hb == "1") {
        runEvery1Minute("heartbeatCheck")
    } else if (hb == "3") {
        runEvery3Minutes("heartbeatCheck")
    } else {
        runEvery5Minutes("heartbeatCheck")
    }
    runEvery5Minutes("watchdogCheck")
}

def refresh() {
    statusReport()
}

def reconnect() {
    try {
        interfaces.rawSocket.close()
    } catch (ignored) {
    }

    state.loggedIn = false
    state.commandInFlight = null
    state.commandQueue = []

    Integer p = safeInt(settings.port, 4025)
    logInfo("Connecting to ${settings.ip}:${p}")
    sendEvent(name: "connectionState", value: "connecting")

    try {
        interfaces.rawSocket.connect(settings.ip, p, byteInterface: false)
    } catch (Exception e) {
        log.error "EyezOn UNO: socket connect failed: ${e}"
        sendEvent(name: "connectionState", value: "error")
        runIn(15, "reconnect")
    }
}

def socketStatus(String message) {
    logDebug("socketStatus: ${message}")
    String m = (message ?: "").toLowerCase()

    if (m.contains("error") || m.contains("close") || m.contains("disconnect") || m.contains("failure")) {
        logWarn("Socket disconnected: ${message}")
        sendEvent(name: "connectionState", value: "disconnected")
        state.loggedIn = false
        state.commandInFlight = null
        runIn(10, "reconnect")
    }
}

def heartbeatCheck() {
    if (device.currentValue("connectionState") == "authenticated") {
        poll()
    }
}

def watchdogCheck() {
    long lastTs = (state.lastFrameTs ?: 0L) as Long
    if (lastTs == 0L) return

    Integer wdMinutes = safeInt(settings.watchdogMinutes, 5)
    long ageSeconds = (now() - lastTs) / 1000L
    if (ageSeconds > (wdMinutes * 60)) {
        logWarn("Watchdog reconnect: no frames for ${ageSeconds}s")
        reconnect()
    }
}

/* ---------------- Outbound UNO commands ---------------- */

private void rawSend(String cmd, String data = "") {
    String frame = "^${cmd},${data ?: ''}\$"
    if (settings.traceLogging) {
        log.debug "EyezOn UNO TX >>> ${cmd == '12' ? '^12,[redacted]$' : frame}"
    }
    interfaces.rawSocket.sendMessage(frame + "\r\n")
}

def poll() {
    enqueueCommand("00", "", "poll")
}

def statusReport() {
    enqueueCommand("0C", "", "statusReport")
}

def armAway(partition = 1) {
    enqueueCommand("09", "${safeInt(partition, 1)}", "armAway")
}

def armStay(partition = 1) {
    enqueueCommand("08", "${safeInt(partition, 1)}", "armStay")
}

def disarm(partition = 1, String code = null) {
    String useCode = code ?: settings.masterCode
    if (!useCode) {
        log.warn "EyezOn UNO: disarm requested without a code"
        return
    }
    enqueueCommand("12", "${safeInt(partition, 1)},${useCode}", "disarm")
}

/* ---------------- Command queue ---------------- */

private void enqueueCommand(String cmd, String data, String name) {
    state.commandQueue = state.commandQueue ?: []
    state.commandQueue << [cmd: cmd, data: (data ?: ""), name: name]
    processQueue()
}

private void processQueue() {
    if (!state.loggedIn) return
    if (state.commandInFlight) return
    if (!(state.commandQueue instanceof List) || state.commandQueue.isEmpty()) return

    def next = state.commandQueue.remove(0)
    state.commandInFlight = [cmd: next.cmd, data: next.data, name: next.name, sentAt: now(), retries: 0]
    logDebug("Sending ${next.name}")
    rawSend(next.cmd as String, next.data as String)
    runIn(4, "commandTimeoutCheck")
}

def commandTimeoutCheck() {
    def inflight = state.commandInFlight
    if (!inflight) return

    long ageMs = now() - (inflight.sentAt as Long)
    if (ageMs < 3500L) return

    if ((inflight.retries as Integer) < 1) {
        inflight.retries = (inflight.retries as Integer) + 1
        inflight.sentAt = now()
        state.commandInFlight = inflight
        logWarn("Retrying ${inflight.name}")
        rawSend(inflight.cmd as String, inflight.data as String)
        runIn(4, "commandTimeoutCheck")
    } else {
        logWarn("Command timed out: ${inflight.name}")
        sendEvent(name: "lastAck", value: "timeout:${inflight.name}")
        state.commandInFlight = null
        processQueue()
    }
}

/* ---------------- Inbound parsing ---------------- */

def parse(String message) {
    if (message == null) return

    String decoded = decodeIncoming(message)
    if (decoded == null || decoded.isEmpty()) return

    state.lastFrameTs = now()
    state.rxBuffer = (state.rxBuffer ?: "") + decoded

    while (true) {
        String buffer = state.rxBuffer ?: ""
        buffer = buffer.replaceFirst('^[\\r\\n]+', '')
        state.rxBuffer = buffer
        if (!buffer) break

        if (buffer.startsWith('%') || buffer.startsWith('^')) {
            int end = buffer.indexOf('$')
            if (end < 0) break
            String frame = buffer.substring(0, end + 1)
            state.rxBuffer = buffer.substring(end + 1)
            parseFrame(frame)
        } else {
            int end = buffer.indexOf('\n')
            if (end < 0) break
            String line = buffer.substring(0, end).replace("\r", "").trim()
            state.rxBuffer = buffer.substring(end + 1)
            handleLoginLine(line)
        }
    }
}

private String decodeIncoming(String message) {
    try {
        byte[] data = hubitat.helper.HexUtils.hexStringToByteArray(message)
        return new String(data, "US-ASCII")
    } catch (ignored) {
        return message
    }
}

private void parseFrame(String frame) {
    if (settings.traceLogging) {
        log.debug "EyezOn UNO RX <<< ${frame}"
    }
    // lastFrame is diagnostic only. Publishing every frame floods device events
    // and makes Current States redraw continuously on a busy TPI connection.
    long frameEventTs = (state.lastFrameEventTs ?: 0L) as Long
    if (now() - frameEventTs >= 60000L) {
        String displayedFrame = frame.take(1024)
        if (device.currentValue("lastFrame") != displayedFrame) {
            sendEvent(name: "lastFrame", value: displayedFrame)
        }
        state.lastFrameEventTs = now()
    }

    if (frame.length() < 5 || frame.charAt(3) != ',' || !frame.endsWith('$')) {
        logDebug("Ignoring malformed UNO frame: ${frame}")
        return
    }
    String cmd = frame.substring(1, 3)
    String data = frame.substring(4, frame.length() - 1)
    if (frame.startsWith('^')) {
        handleAck(cmd, data)
    } else {
        dispatch(cmd, data)
    }
}

private void dispatch(String cmd, String data) {
    switch (cmd) {
        case "01": zoneSnapshot(data); break
        case "02": partitionSnapshot(data); break
        case "06": troubleSnapshot(data); break
        case "05": logDebug("Host information received"); break
        default:
            logDebug("Unhandled UNO report ${cmd}")
            break
    }
}

private void handleLoginLine(String line) {
    switch (line) {
        case "Login:":
            if (!settings.password) {
                logWarn("UNO password is missing")
                sendEvent(name: "connectionState", value: "auth_failed")
                return
            }
            interfaces.rawSocket.sendMessage("${settings.password}\r")
            break
        case "OK":
            state.loggedIn = true
            sendEvent(name: "connectionState", value: "authenticated")
            statusReport()
            break
        case "FAILED":
            logWarn("UNO login failed - check password")
            sendEvent(name: "connectionState", value: "auth_failed")
            break
        case "Timed Out":
            logWarn("UNO login timed out")
            runIn(5, "reconnect")
            break
        default:
            logDebug("Unexpected UNO login response: ${line}")
            break
    }
}

private void handleAck(String cmd, String result) {
    String value = result == "00" ? "ok:${cmd}" : "error:${cmd}:${result}"
    sendEvent(name: "lastAck", value: value)
    if (result != "00") {
        sendEvent(name: "lastError", value: value)
        logWarn("UNO command ${cmd} rejected with code ${result}")
    }
    if (state.commandInFlight?.cmd == cmd) {
        state.commandInFlight = null
        processQueue()
    }
}

private void zoneSnapshot(String data) {
    if (!(data ==~ /(?i)[0-9a-f]{32}/)) return
    String previous = state.zoneBits
    for (int byteIndex = 0; byteIndex < 16; byteIndex++) {
        int bits = Integer.parseInt(data.substring(byteIndex * 2, byteIndex * 2 + 2), 16)
        int oldBits = previous ? Integer.parseInt(previous.substring(byteIndex * 2, byteIndex * 2 + 2), 16) : -1
        for (int bit = 0; bit < 8; bit++) {
            if (previous && ((bits ^ oldBits) & (1 << bit)) == 0) continue
            int zone = byteIndex * 8 + bit + 1
            boolean active = (bits & (1 << bit)) != 0
            parent?.zoneStateChanged(zone, active)
        }
    }
    state.zoneBits = data.toUpperCase()
}

private void partitionSnapshot(String data) {
    if (!(data ==~ /(?i)[0-9a-f]{16}/)) return
    String previous = state.partitionBytes
    Map statuses = ["00": "unknown", "01": "ready", "02": "readyBypassed",
        "03": "notReady", "04": "armedStay", "05": "armedAway",
        "08": "exitDelay", "09": "armedAway", "0C": "entryDelay", "11": "alarm"]
    for (int i = 0; i < 8; i++) {
        String code = data.substring(i * 2, i * 2 + 2).toUpperCase()
        if (previous?.substring(i * 2, i * 2 + 2) != code && code != "00") {
            parent?.partitionStateChanged(i + 1, statuses[code] ?: "busy")
        }
    }
    state.partitionBytes = data.toUpperCase()
}

private void troubleSnapshot(String data) {
    if (!(data ==~ /(?i)[0-9a-f]{16}/)) return
    String previous = state.troubleBytes
    for (int i = 0; i < 8; i++) {
        int bits = Integer.parseInt(data.substring(i * 2, i * 2 + 2), 16)
        if (!previous || previous.substring(i * 2, i * 2 + 2) != data.substring(i * 2, i * 2 + 2)) {
            parent?.partitionTroubleChanged(i + 1, bits != 0)
        }
    }
    state.troubleBytes = data.toUpperCase()
}

/* ---------------- Helpers ---------------- */

private Integer safeInt(def val, Integer fallback) {
    try {
        if (val == null) return fallback
        return Integer.parseInt(val.toString().trim())
    } catch (ignored) {
        return fallback
    }
}

private void logInfo(String msg) { log.info "EyezOn UNO: ${msg}" }
private void logWarn(String msg) { log.warn "EyezOn UNO: ${msg}" }
private void logDebug(String msg) { if (settings.debugLogging) log.debug "EyezOn UNO: ${msg}" }
