# ShortX native importer / implementation-method expansion (2026-10-09)

The published ShortX 1.11 protobuf contract confirms these distinct action families:
- MediaPlayback (action #1) has seven explicit enum modes. All seven now convert to native YAuto android.media.transport, choosing command from the enum. Unknown enum values and unrecognized business fields remain compatibility nodes.
- NoAction (optional decorative icon #1) converts to the existing core.noop, with the icon preserved in source.raw rather than being misrepresented as executable.
- SetVolume (Android stream type #1, stream index #2) now converts for supported types to android.audio.volume.set. YAuto's *single* volume option selects unit=percent or unit=index. Existing percent rules remain unchanged; imported indices are never converted through percentages and are checked against the device's min/max at execution. Unsupported streams and malformed payloads remain compatibility nodes.

Source:
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/MediaPlayback.html
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/MediaPlaybackAction.html
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/NoAction.html
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/SetVolume.html

Not implemented or guaranteed: ShortXPluginAction/ParamsData execution, arbitrary process creation, arbitrary status-bar chips/icons, undocumented source context output semantics, some source rule/Any variants, and ROM-level device parity. These require separate verified implementation and real-device tests. A native mapping hint is not an execution guarantee.
