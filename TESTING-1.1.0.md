# Version 1.1.0 verification

This release replaces FPM x Compat. Its runtime verification requires that addon to be absent, while retaining FirstPerson and the supplied Minecraft instance's equipment/animation mods. Earlier results that used the addon are preserved in [TESTING-1.0.0.md](TESTING-1.0.0.md); they are historical regression evidence, not proof of the replacement.

Target environment: Minecraft 1.21.1, NeoForge 21.1.264, EMF 3.3.12, ETF 7.2.6, FA Player Extension 1.1, Fresh Animations 1.11.1 and FirstPerson 2.7.3. The copied shader configuration uses Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5. Testing uses a disposable integrated world and copies of the installed pack, with no existing user saves or multiplayer server.

The tested production artifact is `emf-attachments-compat-1.21.1-1.1.0.jar`, SHA-256 `AA10E8BD17E3D8664BE760DCD28AD69224BC260300BA2105E501C0631EC87AAA`. Eight JUnit regression tests pass, covering wearable transforms/native flower geometry, visibility policy, and player-scale offset composition.

The matching production jar is installed in the supplied CurseForge instance with FirstPerson 2.7.3 enabled and FPM x Compat absent. The original FirstPerson configuration hash is unchanged, and no developer harness is installed there. [Environment and installation verification](verification/environment.json) records the hashes.

## Runtime evidence

| Run | Scope | Status |
| --- | --- | --- |
| `replacement-without-firstperson` | Full wearable sweep without FirstPerson | PASS: 1,604 renders, zero failures |
| `replacement-firstperson-default` | Full standard-model sweep, scoped renderer/configuration probes | PASS: 1,604 renders, zero failures; 224 scope scenarios and native Curios callback checks pass |
| `replacement-firstperson-slim` | Full slim-model sweep | PASS: 1,604 renders and all scoped/combat probes |
| `replacement-rear` | Rear attachments and native quiver draw checks | PASS: 35 cases / 140 renders; all 7 pages reviewed |
| `replacement-live` | Actual camera, equipment and item-use transitions | PASS: all 29 screenshots reviewed; 8 use stages advanced naturally by 17 ticks with input/configuration restored |
| Combat/animation regression | Complete reported attacks, fade-out, rendering ownership, configuration preservation | Runtime probe PASS for four attacks; all 36 native camera captures reviewed, 527/527 owned frames draw one body and one weapon |
| NeoForge configuration screen | Open, edit, save/reopen, file persistence and extended offset range | PASS: arm Z=-6 saved to TOML and survived reopening; original 2 restored |

The existing discovery covers **327 item IDs and 400 actual item/slot/index combinations**, plus one blue-dyed flower headwear variant. Four poses give **1,604 renders per full sweep**. Numerical checks cover errors, finite geometry, shared model mutation, corrected head/torso anchors, flower color and native 144-vertex petal geometry. Screenshots receive visual review; capture alone is not a pass.

New scoped tests call the actual transformed Accessories layer with normal and cosmetic stacks on local and remote players. They compare normal rendering with the local FirstPerson body pass, a remote render during that body flag, camera extraction, third person, a different camera entity, FirstPerson disabled, and an Iris shadow-pass flag. Head-slot/scarf disable options, exact visible-item overrides and custom hidden-item entries are tested through real renderer output. Two repeated draws per case must leave inventory components, cosmetic stacks, persistent render flags, scarf attachment state and player model transforms unchanged. Temporary fixtures and in-memory configuration are restored in `finally`; the probe does not save configuration.

The transformed native Curios callback is also exercised with actual registered renderers, including cosmetic contexts with `visible=false`. This is hook-level evidence in the installed Accessories bridge environment, not a separate Curios-only client launch. The EMF marker probe mounts genuine local/remote render states and verifies that hidden head/face geometry is suppressed only in the intended local body context. The caller's render-state stack is restored and checked.

The first replacement full sweep completed all 401 variants in four poses with no discovery errors, render exceptions, invalid vertices, shared pose mutations or targeted anchor/color/geometry failures. All **224 visibility/configuration scenarios** passed twice through the Accessories layer and twice through the transformed native Curios callback. The rejected addon was absent, and fixture/configuration restoration passed. Local hidden head/face emitted zero vertices; remote, absent-entity, camera-extraction, third-person, disabled-FirstPerson and shadow controls each preserved 48 vertices. Goggles and native red/blue flower shapes were visually checked in the new full sweep.

The installed Better Combat probe completed one-handed slam, two-handed slam, and both two-handed stab variants through their natural 36–42 tick lifetimes and 16–18 tick explicit fades. Rendering ownership remained correct until the full layer ended, correction weights decayed to zero, and the original FirstPerson/Better Combat/Not Enough Animations settings were preserved. Actual transformed body-offset tests at scales 0.5, 1 and 2 retained later external-handler contributions. Disabling the correction and activating the installed Combat Roll relinquished rendering ownership as expected.

The separate native attack camera sequence used actual installed weapon/combo definitions for one-handed slam, two-handed slam and two-handed stab, each at level/up/down camera angles. All **36 windup/strike/recovery/completion screenshots** were reviewed. All **527 owned rendering frames** contained exactly one body pass and one weapon render, with zero competing vanilla or extra item renders and no violations. Original settings were restored. See the [combat visual review](verification/replacement-combat-visual-review.json) and [combat summary](verification/combat-summary.json).

The final live camera sequence captured and visually passed **29 stages**: 21 equipment/camera/cape transitions and eight active food, drink, shield and spyglass uses. The use fixtures retain the same stack, hold the use key, begin with the item's correct duration, and advance naturally by **17 ticks** before capture. Food/drink cases cover NEA eat/drink enabled and disabled plus third person. At capture, first-person food/drink/shield each use one body and one held-item render with no competing vanilla/other item draw. Spyglass draws no body or held item in its scoped view; third-person food/drink each draw one ordinary held item. NEA settings and input state are restored without saving configuration. Food particle geometry is separate from these held-item counts. See the [equipment/camera review](verification/replacement-camera-visual-review.json) and [final item-use review](verification/replacement-use-visual-review.json).

The native NeoForge configuration screen was opened through Mods. Both setting categories were accessible. Saving arm Z=-6 updated the TOML, and closing/reopening the screen retained that value; the original value 2 was then restored and verified on disk. See [configuration evidence](verification/replacement-config-ui.json).

## Reported issues and scope

[COMPATIBILITY.md](COMPATIBILITY.md) and the [issue audit/test plan](research/inventory/fpm-replacement-test-plan.md) link the upstream reports. Applicable targets include accessory console spam, inaccessible NeoForge configuration, the requested -6 offset, visible headwear/cosmetics, third-person interference, and the installed Better Combat attack/configuration interactions. Tests preserve the user's existing Not Enough Animations and FirstPerson settings.

Punchy, Pehkui, Cinematic Interactions, Essential, TACZ, Vic's Point Blank, Trydea's Meleez and other requested but absent/platform-specific integrations are not certified by this instance's tests. Vanilla player-scale tests do not establish Pehkui compatibility. The replacement preserves external render vetoes rather than claiming support for every alternate first-person renderer.

## Records and limits

- [Summary CSV](verification/summary.csv) and [summary JSON](verification/summary.json) separate `replacement-` runs from earlier `final-` and baseline evidence.
- [Live summary](verification/live-summary.json) points to camera captures and their raw reports.
- [Combat summary](verification/combat-summary.json) records live attack captures and body/weapon draw counts.
- [Raw reports and screenshots](.local/client/emfattachments-tests/) retain per-item and per-pose evidence.
- [README reproduction steps](README.md#reproduce-the-runtime-tests) describe the isolated client and triggers.

Coverage concerns the runtime-discovered default wearable stacks in this installed configuration plus one dye variant. It does not enumerate every NBT/component value, equipment combination, animation transition, shader, skin texture or server condition. Targeted runtime probes, visual camera review and source-level reasoning are reported separately.
