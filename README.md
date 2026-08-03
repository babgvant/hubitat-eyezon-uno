# Hubitat EyezOn UNO Integration

Direct local integration between Hubitat Elevation and an EyezOn UNO alarm panel — no
cloud, no separate proxy service. Talks straight to the UNO's TPI socket on port 4025
using the DSC EnvisaLink TPI protocol (confirmed by EyezOn support to be the same
protocol the UNO implements).

## Contents

- `apps/EyezOn-UNO-App.groovy` — setup app; creates and manages child devices
- `drivers/EyezOn-UNO-Connection.groovy` — owns the raw TCP socket, login, command
  queue, and TPI frame parsing
- `drivers/EyezOn-UNO-Partition.groovy` — per-partition arm/disarm/status device

Zone devices use Hubitat's built-in Generic Component sensor drivers (Contact,
Motion, Smoke, Carbon Monoxide, Water) rather than custom ones.

## Install

1. In Hubitat, open **Drivers Code**, **New Driver**, paste in
   `drivers/EyezOn-UNO-Connection.groovy`, save.
2. Repeat for `drivers/EyezOn-UNO-Partition.groovy`.
3. Open **Apps Code**, **New App**, paste in `apps/EyezOn-UNO-App.groovy`, save.
4. From **Apps**, **Add User App**, choose **EyezOn UNO Integration**.
5. Enter the UNO's IP address, TPI port (default 4025), and TPI password (same as the
   UNO's local web page password), plus zone/partition counts and optional zone hints.

## Status

Verified offline (Groovy compile check through class generation, plus unit tests for
the checksum routine and frame parsing against the EnvisaLinkTPI-1-08 spec's own
worked examples). **Not yet verified against real UNO hardware.** Before relying on
it, enable trace logging on the connection device and compare the raw frames it logs
against a known-working reference (e.g. an existing EnvisaLink integration on the same
panel) to confirm the event codes line up on your firmware revision.

## Protocol notes

Frames are `CCC` (3-digit command) + data + `CKS` (2-hex-char checksum: sum of the
ASCII byte values of every command/data character, truncated to 8 bits) + CR/LF.
Login: on connect the panel sends `505` with data `3` (password requested); reply with
`005` + password; panel replies `505` with `1` (success), `0` (bad password), or `2`
(timed out). See EnvisaLinkTPI-1-08 for the full command/event tables.
