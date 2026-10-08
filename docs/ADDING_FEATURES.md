# Adding or removing features

## Add

1. Choose a permanent ID such as `device.nfc.set`.
2. Add one descriptor and executor inside the owning FeaturePack.
3. Choose the user-facing semantic `FeatureDomain`. Existing IDs get a compatibility inference,
   but new or ambiguous features should set `domain = FeatureDomain.…` explicitly.
4. Declare required capability IDs and access requirements.
5. Keep Root, Shizuku, LSPosed, Zygisk and Accessibility as implementation/access metadata.
   They are badges in the picker, not feature categories.
6. Add a migration only when persisted config shape changes.
7. Add tests.

Do not edit the engine, home screen or selector for a normal new feature.

## Remove

Do not delete a released persisted ID immediately. Mark it deprecated and hide it from new selection while keeping the reader/migration. Remove the entire pack only when compatibility policy permits it.


## Picker taxonomy

The feature picker is intentionally organized by what the user wants to automate, not by the
backend that implements it. `FeatureCategory` is retained as a legacy/internal compatibility bucket;
normal picker navigation uses `FeatureDomain + FeatureKind`.

Examples:

- Wi-Fi implemented through Shizuku or Root -> **Connectivity**
- LSPosed hardware-key trigger -> **User input & UI**
- Root reboot -> **Battery & power**
- HTTP request -> **Web & network requests**
- Geofence -> **Location**
- LLM/image model action -> **AI**

If a feature could be interpreted more than one way, prefer the domain a user is most likely to
browse first. Search still indexes the semantic category title and subtitle, so related terms remain
discoverable.
