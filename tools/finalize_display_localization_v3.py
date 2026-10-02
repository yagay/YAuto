#!/usr/bin/env python3
from __future__ import annotations

import finalize_display_localization_v2 as v2


def main() -> None:
    v2.main()
    v2.base.replace_once(
        'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroAutomationEditorScreen.kt',
        '                            subtitle = feature?.let(::featureSummary),',
        '                            subtitle = feature?.let { featureSummary(it, descriptors) },',
    )
    print('Localized feature-summary call sites finalized (v3).')


if __name__ == '__main__':
    main()
