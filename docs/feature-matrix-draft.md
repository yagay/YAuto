# YAuto feature matrix baseline

This file tracks implementation parity by semantic capability rather than copying proprietary code or resources.

## UX architecture
- Macro-style category → feature → parameter hierarchy
- Shared Event / State / Action / Constraint picker
- Search, favourites and recents
- Installed-app picker
- Structured ActionNode editor for branches, loops, parallel, try/catch and reusable flows

## Implemented capability groups
- Android lifecycle and package events
- Notification events and active-notification actions
- Foreground app/window events via Accessibility
- Screen, battery, charging, power-save and network states
- Wi-Fi/mobile data/Bluetooth/airplane privileged controls
- Audio streams, ringer, media, microphone and speakerphone
- Clipboard read/write/change events
- Torch and DND
- Intents, URI, dial/SMS/email/maps/settings
- HTTP requests with structured output
- File/text/directory operations
- Variables, lists, objects, regex and encoding/hash transforms
- Time/date windows
- Device/storage/sensor/location data
- Workspace-aware sensor triggers
- Interactive overlay Surface foundation
- Root / Shizuku / LSPosed capability broker
- MacroDroid / Tasker / ShortX importers with lossless compatibility fallback

## Remaining parity groups
- Continuous location/geofencing
- Bluetooth device connection details and Wi-Fi scan/network detail triggers
- Telephony/SMS incoming events where Android roles/permissions permit
- NFC/MIDI
- OCR/CV/image matching
- WebSocket/WebDAV and richer scripting
- Surface variants: dialog, bubble, sidebar, pie, widget and QS tile
- Plugin/Tasker-compatible external provider bridge
- Richer SystemUI/system_server hooks
- Expanded verified importer mappings
- Standalone mini-app export
