# Adding or removing features

## Add

1. Choose a permanent ID such as `device.nfc.set`.
2. Add one descriptor and executor inside the owning FeaturePack.
3. Declare required capability IDs.
4. Add a migration only when persisted config shape changes.
5. Add tests.

Do not edit the engine, home screen or selector for a normal new feature.

## Remove

Do not delete a released persisted ID immediately. Mark it deprecated and hide it from new selection while keeping the reader/migration. Remove the entire pack only when compatibility policy permits it.
