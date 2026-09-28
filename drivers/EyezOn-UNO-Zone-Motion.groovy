/**
 *  EyezOn UNO Zone Driver - Motion
 *  Version: 1.0.4
 *
 *  Child device for a zone configured as a motion/PIR sensor. State is pushed in by
 *  the parent app via parse() whenever the connection driver reports a zone event.
 */
metadata {
    definition(name: "EyezOn UNO Zone Motion", namespace: "eyezonUno", author: "babgvant") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "MotionSensor"
    }
}

def installed() {
    sendEvent(name: "motion", value: "inactive")
}

def parse(List<Map> events) {
    events?.each { Map evt -> sendEvent(evt) }
}

def refresh() {
    parent?.refreshAll()
}
