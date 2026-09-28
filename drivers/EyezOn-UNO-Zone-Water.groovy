/**
 *  EyezOn UNO Zone Driver - Water
 *  Version: 1.0.4
 *
 *  Child device for a zone configured as a water/flood sensor. State is pushed in by
 *  the parent app via parse() whenever the connection driver reports a zone event.
 */
metadata {
    definition(name: "EyezOn UNO Zone Water", namespace: "eyezonUno", author: "babgvant") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "WaterSensor"
    }
}

def installed() {
    sendEvent(name: "water", value: "dry")
}

def parse(List<Map> events) {
    events?.each { Map evt -> sendEvent(evt) }
}

def refresh() {
    parent?.refreshAll()
}
