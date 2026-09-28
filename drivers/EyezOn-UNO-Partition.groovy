/**
 *  EyezOn UNO Partition Driver
 *  Version: 1.0.4
 *
 *  Child device representing one partition on the panel. All commands are proxied
 *  through the parent app to the EyezOn UNO Connection device, which owns the socket.
 */
metadata {
    definition(name: "EyezOn UNO Partition", namespace: "eyezonUno", author: "babgvant") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Alarm"

        attribute "securitySystemStatus", "string"
        attribute "troubleState", "string"
        attribute "lastUserAction", "string"

        command "armStay"
        command "armAway"
        command "disarm", [[name: "Code", type: "STRING"]]
        command "off"
        command "strobe"
        command "siren"
        command "both"
    }
}

def installed() {
    sendEvent(name: "securitySystemStatus", value: "unknown")
    sendEvent(name: "troubleState", value: "clear")
    sendEvent(name: "alarm", value: "off")
}

private Integer partitionNumber() {
    String dni = device.deviceNetworkId ?: ""
    int idx = dni.lastIndexOf('-')
    if (idx < 0) return 1
    try {
        return Integer.parseInt(dni.substring(idx + 1))
    } catch (ignored) {
        return 1
    }
}

def refresh() {
    parent?.refreshAll()
}

def armStay() {
    parent?.armStay(partitionNumber())
    sendEvent(name: "lastUserAction", value: "armStay", isStateChange: true)
}

def armAway() {
    parent?.armAway(partitionNumber())
    sendEvent(name: "lastUserAction", value: "armAway", isStateChange: true)
}

def disarm(String code = null) {
    parent?.disarm(partitionNumber(), code)
    sendEvent(name: "lastUserAction", value: "disarm", isStateChange: true)
}

def off() { disarm() }
def strobe() { logUnsupported("strobe") }
def siren() { logUnsupported("siren") }
def both() { logUnsupported("both") }

private void logUnsupported(String cmd) {
    log.warn "EyezOn UNO Partition: '${cmd}' is not a panel output the UNO connection exposes"
}

def setStatus(String status) {
    String value = status ?: "unknown"
    sendEvent(name: "securitySystemStatus", value: value, isStateChange: true)
    sendEvent(name: "alarm", value: (value == "alarm") ? "both" : "off", isStateChange: true)
}

def setTrouble(boolean active) {
    sendEvent(name: "troubleState", value: active ? "active" : "clear", isStateChange: true)
}
