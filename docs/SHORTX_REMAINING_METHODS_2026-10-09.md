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

Updated 2026-10-10: non-plugin app-process requests and YAuto-scoped SystemUI chips/status icons now have native implementations (see SHORTX_NON_PLUGIN_IMPLEMENTATION_2026-10-09.md), but still need OEM device testing. Arbitrary third-party status slots, process lifetimes, undocumented art formats, source context side effects, unknown Any variants and ShortXPluginAction/ParamsData execution are not supported. Structure actions with source-only metadata, unrecognized fields or invalid timing remain lossless compatibility nodes instead of silently changing semantics.

## 2026-10-10: JSON control-flow parity without duplicate picker functions

- Protobuf ConditionOperator NONE (2) now converts into YAuto's existing PredicateNode.None instead of remaining a compatibility wrapper. MVEL (3) still stays source-preserved.
- Verified JSON ShortX 1.11 control-flow nodes: sequential IfThenElse / WhileLoop, no-field StopAllActions, current-scope BreakActionExecute, SetFunctionReturnValue, ExecuteFunction, FromDA. Native conversions reuse existing YAuto ActionNode structures and stable flow IDs, without introducing redundant feature IDs or menu entries.
- Unrepresentable async/loop timing, non-default action metadata, source IDs/notes, MVEL expressions, unknown keys or duplicate parameter names remain complete compatibility nodes. Nested unknown actions continue to be represented within converted safe branches.
- Protocol reference: https://github.com/ShortX-Repo/ShortX-Files/blob/main/skills/references/actions.md . Debug unit tests are not OEM/runtime validation.
