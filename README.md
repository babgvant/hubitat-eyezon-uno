# Hubitat EyezOn UNO Integration

Version: 1.0.4

Work in progress on a direct local integration between Hubitat Elevation and an
EyezOn UNO alarm panel. The driver uses EyezOn's UNO TPI on port 4025, without
a cloud service or separate proxy.

## Contents

- `apps/EyezOn-UNO-App.groovy` — setup app; creates and manages child devices
- `drivers/EyezOn-UNO-Connection.groovy` — owns the raw TCP socket, login, command
  queue, and TPI frame parsing
- `drivers/EyezOn-UNO-Partition.groovy` — per-partition arm/disarm/status device
- `drivers/EyezOn-UNO-Zone-Contact.groovy` — zone device: door/window contact
- `drivers/EyezOn-UNO-Zone-Motion.groovy` — zone device: motion/PIR
- `drivers/EyezOn-UNO-Zone-Smoke.groovy` — zone device: smoke/heat detector
- `drivers/EyezOn-UNO-Zone-CO.groovy` — zone device: carbon monoxide detector
- `drivers/EyezOn-UNO-Zone-Water.groovy` — zone device: water/flood sensor

Zone devices use this repo's own drivers (rather than Hubitat's built-in Generic
Component sensors) so they install cleanly through Hubitat Package Manager.

## Install

### Hubitat Package Manager (recommended)

In HPM, choose **Install**, **Search by Keywords**, and search for "EyezOn UNO", or
use **Install from a URL/Manifest** and paste the raw URL to `packageManifest.json`
in this repo. HPM installs the app and all seven drivers and keeps them updated.

### Manual

1. In Hubitat, open **Drivers Code**, **New Driver**, paste in
   `drivers/EyezOn-UNO-Connection.groovy`, save.
2. Repeat for `drivers/EyezOn-UNO-Partition.groovy` and each
   `drivers/EyezOn-UNO-Zone-*.groovy` file.
3. Open **Apps Code**, **New App**, paste in `apps/EyezOn-UNO-App.groovy`, save.
4. From **Apps**, **Add User App**, choose **EyezOn UNO Integration**.
5. Enter the UNO's IP address, TPI port (default 4025), and TPI password (the
   password for the UNO's local page), plus zone/partition counts and optional
   zone hints. Alarm control still needs validation on UNO hardware.

## Status

Real UNO hardware has been reached on port 4025. Direct protocol checks confirmed
the `Login:` / `OK` exchange and initial `%01`, `%02`, `%04`, `%05`, and `%06`
reports on UNO firmware 01.01.193. Read-only poll (`^00,$`), initial state dump
(`^0C,$`), and host information (`^0D,$`) commands returned successful
acknowledgements and the expected reports. The driver and app passed a Groovy
syntax check. These checks do not test the driver inside Hubitat or verify zone
changes, reconnects, polling over time, or arm/disarm.
Do not rely on this integration for alarm control until those checks pass.

## Protocol notes

The UNO sends `Login:` on connection. The client replies with the local-page
password and a carriage return; `OK` confirms login. UNO reports use `%CC,DATA$`
and client commands use `^CC,DATA$` followed by CR/LF. For example, a partition
state report is `%02,0100000000000000$` and a host information request is
`^0D,$`. The command comma is required even when DATA is empty. See EyezOn's
[UNO TPI documentation](https://forum.eyezon.com/viewtopic.php?t=5479); it
explicitly distinguishes UNO TPI from DSC EnvisaLink TPI.
