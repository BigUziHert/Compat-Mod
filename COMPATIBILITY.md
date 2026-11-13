# FirstPerson compatibility replacement

Version 1.2.15 extends the FPM x Compat replacement introduced in 1.1.0 for this Minecraft 1.21.1 / NeoForge instance. EMF, ETF and FirstPerson are optional; installation preserves their current presence or absence. Independent corrections remain active without EMF/ETF, while EMF animation fixes require EMF 3.3.12 or newer and its own compatible ETF dependency. EMF bridge mods may still require EMF and must be disabled or removed separately when removing it. The replacement neither bundles nor calls FPM x Compat, and the installer removes that companion from the instance's mods directory.

## Entity Culling 1.12.1 update audit — 2026-11-13

Version **1.2.15** extends the verified Sable 2.0.6 reduced-tick integration to Entity Culling **1.12.1**. The relevant upstream tick/render classes and helper are byte-identical to 1.12.0. The existing maintenance and transition logic passes both copied-client runtime suites and fresh woven-class checks; no rendering algorithm changed. The installed 1.2.14 baseline loads but disables this optimization, and its historical fallback fails the deliberately seeded removed-support cleanup control. That finding is limited to the tested stale-state condition, not a reproduced ordinary multiplayer unload incident. See [the current verification record](TESTING.md#version-1215-verification-record). Later upstream versions remain outside the verified range.

## Ragdoll and Entity Culling update audit — 2026-11-12

The three supplied installed jars were inspected directly. Entity Culling **1.12.0** moves `NMSCullingHelper` into `dev.tr7zw.entityculling.util`, breaking the old required Sable tick injection. The development patch accepts both helper locations and still requires exactly one match inside `ClientWorldMixin.tickEntity`; it does not bypass render culling. Developer harness and performance imports now use the current package.

Ragdoll Reactions **0.8.0** retains byte-identical impact-handler and suppression classes from the archived 0.7.0 jar, so the Tidal Claws guard remains applicable. Its required Sable Player Ragdoll **0.8.1** is installed. That ragdoll update retains the accessory/Curios/camera helper classes from archived 0.7.5; its renderer adds an EMF vanilla-model scope while preserving our injection signatures and accessory draw order. No additional compatibility change was identified for either ragdoll mod.

The updated source passes the bytecode contract tests against Entity Culling 1.12.0 and archived 1.11.3. A disposable startup reproduced the old 1.2.11 injection failure with Ragdoll Revival omitted; the final 1.2.13 candidate starts with all three updated mods and Ragdoll Revival retained. The installed compatibility jar remains unchanged. Startup, functional checks and their limits are recorded in [TESTING.md](TESTING.md#mod-update-follow-up--2026-11-12).

## Reduced Sable player ticks in 1.2.13

With the verified Sable **2.0.6** and Entity Culling **1.12.0** releases, stable remote players on live Sable supports can use Entity Culling's reduced tick when hidden or outside the camera. The reduced path keeps native old-position bookkeeping and remote-player interpolation, and calls Sable's own attachment projection and cleanup callbacks before interpolation, in the same order as a full tick. It does not copy Sable's movement logic or force hidden models to render.

Pending attachments, mismatched or removed supports, detachment, sleeping/dead players and mounted players retain full-tick fallbacks where required. Native local-player, camera and other culling exemptions still apply. Other dependency versions retain the previous full-tick protection; the reduced path also falls back if its native callback bridge cannot be linked. `-Demfattachments.sableFullTicks=true` selects that full-tick behavior for comparison or troubleshooting.

Four matched offline performance runs, two per mode, measured **2.77%** higher mean FPS with eight hidden equipped players and **3.37%** with 24; the corresponding visible scenes changed by **-0.72%** and **+0.03%**. Both natural and injected-cull functional comparisons passed. These results use client-only remote player clones on a real Sable platform, not networked multiplayer. They do not certify Ragdoll Revival gameplay or guarantee the same gain in other scenes. See the [1.2.13 verification report](verification/sable-reduced-1.2.13/REPORT.md).

## EMF Compat update audit — 2026-11-09

The NeoForge 1.21.1 releases were compared with our patches. The November 11 follow-up executes the installed bridge code to distinguish replaceable corrections from partial pose overlaps. Two corrections now yield to Core:

| Upstream behavior | Ownership in 1.2.12 |
| --- | --- |
| [Core 2.4.0](https://modrinth.com/mod/emf-compat-core/version/upzqIWB0): steady Sable height, including tilted decks | Core adjusts EMF's `getEntityY()` result. Our deck pose/motion calculations and old contact tolerance are bypassed with Core 2.4.0+. Fresh evaluation/context hooks retain only a one-downward-float history guard, capped at 0.0001 blocks, because the full handoff reproduced a small false landing. Older/missing Core retains the full fallback. |
| Core crouch normalization, verified in 2.3.2 and 2.4.0 | Skip only our added crouched-combat Y offset while Core's normalizer is enabled. Core runs after PlayerAnimator and cancels that offset. Retain the fallback when Core is absent, older than the verified API, globally disabled or `core.crouchFix` is off. |
| Core 2.4.0: EMF continues animating throughout fade-out | Retain our first-person combat coordination, native arm visibility and separate interpolation history. These handle renderer ownership and camera contexts; they do not implement Core's animation-pause linger. Core's published `PoseInterpolator` and `PartFade` classes are byte-identical between 2.3.2 and 2.4.0. |
| Core 2.4.0: shared `emfc_*` player-state variables | New API, with no matching state-variable implementation in this mod. It does not replace our Artifice gun-transform preservation. |
| NEA bridge 1.2.1 → [1.2.2](https://modrinth.com/mod/not-enough-animations-emf-compat/version/5woW9GNz) | All three published class files are byte-identical; the update narrows Minecraft version metadata. Keep our custom bow/crossbow preservation and local first-person corrections. |
| Better Combat bridge 2.1.1 → [2.1.2](https://modrinth.com/mod/emf-compat-better-combat/version/J3iuwAGp) | Upstream changes attack-start easing, removes its mace-slam modification and publishes arm state. Our camera/render coordination and collision-axis correction remain separate. |
| Carry On 2.1.1 → [2.2.0](https://modrinth.com/mod/emf-compat-carry-on/version/rWi4iYwg); Create 2.1.0 → [2.1.1](https://modrinth.com/mod/create-emf-compat-skyhook/version/w9CLlrNR); Supplementaries 1.1.1 → [1.1.2](https://modrinth.com/mod/emf-compat-supplementaries/version/ify2HjAy) | State publication, field-name conflict fixes and/or version metadata changes; no matching local patch to retire. Carry On 2.2.0 requires Core 2.4.0. |

The Sable height source follows cached installed Core metadata; optional Core classes are linked only after that check. On Core 2.4.0+, use Core's **Sable: steady height on sub-levels** option (`core.sableSteadyHeight`, enabled by default). Disabling that option or Core's global compatibility switch resets both precision histories and leaves the full fallback inactive. Other Sable sleeping, Entity Culling and ragdoll fixes remain enabled under their existing conditions. This audit concerns the EMF family shown in the update list; it does not establish Vista or Moonlight compatibility.

The 1.2.11 assumption that the complete Sable helper was redundant was too broad. Fresh isolated comparisons with identical upstream jars reproduced a local descent-onset landing pulse with every helper disabled, while the 1.2.10 helper prevented it. That helper consumes Core-adjusted Y, so there is no demonstrated harmful double correction in this sequence. Version 1.2.12 keeps only the proven precision protection on current Core instead of continuing the full legacy deck-motion work.

Source details: [Sable height calculation](https://github.com/victorkozhokin/emf-compat/blob/aa1a523d4172159ab1b1c418d2d655688c207f7e/core/neoforge/1.21.1/src/main/java/strm/emfcompat/core/SubLevelHeight.java), [EMF height hook](https://github.com/victorkozhokin/emf-compat/blob/aa1a523d4172159ab1b1c418d2d655688c207f7e/core/neoforge/1.21.1/src/main/java/strm/emfcompat/core/mixin/EMFMathMixin.java), [fade-out change](https://github.com/victorkozhokin/emf-compat/commit/b74fe2f26b476c93bbc85a10fa0f7bd22eb5dc60). Runtime validation and its limits are recorded in [TESTING.md](TESTING.md).

The remaining partial overlaps are not interchangeable. Better Combat's bridge reduces the camera-pitch adjustment on the root body; it does not remove the attack keyframes' root/torso tilt that our local FirstPerson correction handles. Core's generic arm restoration has no Artifice gun-pose producer, and its smoothing, visibility and body-follow behavior differ from preserving Iron's native gun transforms. Its interpolation also shares history across body/shadow views. Tests using the installed implementations cover those differences; there is no broad claim that unrelated future mod combinations cannot overlap.

## Reported problems and implementation

Reviewed the [CurseForge comments](https://www.curseforge.com/minecraft/mc-mods/first-person-model-compat/comments) and linked [issue tracker](https://github.com/twk0d/fpm-x-compat/issues) on 2026-10-24. Reports are reproduction leads, not proof that every installation has the same fault.

| Report | Replacement behavior |
| --- | --- |
| Iron's six-shooter uses different numbers of hands in first and third person | The gun hand fires and reloads in both views. FirstPerson renders the free arm and its accessory when visible. The six-shooter keeps Iron's native ammunition, recoil and reload behavior. |
| Iron's guns lose native holding/reload poses in third person with Fresh Player | Preserve the actual selected native arm transforms through EMF evaluation, carrying the arms with animated torso translation. Retain native reload progress, rifle support arm, pistol arm selection and current visibility. Does not alter gameplay or claim global animation pauses. |
| Accessories disappear from Iron's first-person gun hands | Draw the selected equipped/cosmetic accessory through its native first-person API at Iron's actual animated arm bone. Restore Artifacts and Skyjade's post-reset arm pose for this draw only; native Aether uses the supplied arm model. Hidden slots and physical hand selection remain native. This path does not require EMF, ETF or FirstPerson. |
| FirstPerson replaces Iron's native gun view and drops reloads below the camera | Register actual gun items through FirstPerson's runtime native-hands API. Keep the body while delegating gun/hands to Iron and hiding body arms/items. A scoped geometry mask hides the body arms replaced by native hands, including copied gloves through EMF and downward views. Preserve the free arm for one-handed pistols and hand it to the native animation during two-handed reloads; normal body gloves return afterward. Preserve persisted settings and re-register on settings reload. |
| One Whoopee Cushion becomes three during/after a roll or ragdoll | With Sable Player Ragdoll 0.7.5+, route known head geometry to the physical head regardless of flexible storage slot, then apply exact bridged-draw deduplication. Preserve selected cosmetics, hidden flags and independent equipped items. |
| Aether/Skyjade hand accessories gain an extra pair during ragdolls | Route canonical paired-arm slots to both physical arms in the Curios bridge, so the existing per-slot deduplication suppresses its extra torso pair. Omit the unused opposite-half geometry in each physical glove draw without mutating model state. Preserve native feet routing. |
| Sleeping player lies at the wrong angle on a rotated Sable bed | Keep Sable's dedicated bed rotation and suppress its duplicate general player rotation only for a sleeping client player on a live sublevel bed. Skip the matching inverse rotation in name tags so they still face the camera. Ordinary beds, awake players, camera rotation and gameplay stay native. |
| Night Vision Goggles or another head wearable intrudes into the sleeping camera | Apply the existing head/scarf visibility policy during the local sleeping player's first-person world draw, even without FirstPerson. Preserve third-person/other-player and shader-shadow draws, slot visibility and exact-item overrides. This covers the Accessories/Curios paths, not arbitrary custom armor or shield renderers. |
| Native animated hands falsely react to falling/crouching on a moving Sable boat | With Core older than 2.4.0 or absent, keep independent EMF first-person-hand and world/shadow motion histories. Filter only carried deck motion; preserve intentional crouching, jumping and actual landings. Core 2.4.0+ owns the height correction. |
| Aether gloves are absent from Better Combat's native first-person attack arms | Supplement the filtered Accessories layer with only the exact native Aether glove renderer. Use the native scaled body pose and mirrored arm visibility; retain hidden slots, cosmetics and cloak rules. Applies with FirstPerson absent. |
| Diamond gloves detach while shrunk, also in third person, with an unidentified Simply Swords weapon | The user confirmed on October 27 that the 1.2.6 glove change resolved this symptom in their gameplay. The isolated ordinary-ground tests did not reproduce this separate detachment; distinguish the user-confirmed result from the directly reproduced native attack omission. |
| Fresh Player repeatedly bends while standing or walking on a moving Sable deck | With Core older than 2.4.0 or absent, correct Fresh Player's previous-height and dragged-height animation history for deck movement, without changing physical crouch, movement or network data. Recognize the installed Fresh animation contract and update only when EMF actually evaluates it. Skip all four hooks with Core 2.4.0+ to avoid overlapping its height correction. |
| Players on Sable sub-levels can become stuck behind Entity Culling's tick suppression; the old broad exemption also rendered hidden players | On verified Sable 2.0.6 with Entity Culling 1.12.0 or 1.12.1, keep reduced ticks for stable remote attachments and restore only Sable's missing native maintenance before interpolation. Pending, stale and detaching attachments receive full ticks for recovery; other dependency versions retain historical live-support protection with the removed-support limitation recorded above. Native render culling, server tracking, packet delivery, actual invisibility and ordinary world-player culling remain unchanged. |
| Onion Ring and other Artifacts hand accessories stay still while native EMF hands animate | Snapshot the same-frame animated arm supplied by Accessories, apply it after Artifacts resets its glove model, and restore the glove pose afterward. Covers all ten Artifacts glove/ring items, including their native glow draw. Works with FirstPerson absent and respects EMF's hand-animation option. |
| Avoidable temporary allocations in inactive FirstPerson arm masking | Reuse inactive scopes and immutable native visibility combinations; allocate identity maps only for actual masks. This is an allocation reduction, not a demonstrated fix for the reported multiplayer stutter with FirstPerson absent. |
| Whoopee Cushion in Face renders on both head and torso while downed | Route bridged head slots using the installed Accessories classifier, so the existing per-part duplicate suppression catches both API paths. Preserve native hand and feet routing. |
| Hidden Face accessory reappears during Ragdoll Revival | Before rendering a stored item, consult the actual online owner's Accessories flag and the renderer's native visibility override. Live draws retain native checks; missing/offline owners retain native fallback because stored snapshots omit visibility flags. |
| Cosmetic replacement and functional item both appear on a ragdoll | Suppress the functional Curios fallback only after a successful Accessories draw matching the current cosmetic in the exact same owner/slot/index. Keep failed-renderer and missing-owner fallbacks. |
| Floating extra claw during Sable ragdolls | Scope the actual physical arm around each native accessory call and reject only a renderer-selected opposite arm before Artifacts/Cataclysm models re-enable its geometry. Keep the existing exact bridge dedup. |
| Artifacts necklace disappears into the running torso | Preserve the copied torso translation outside the native necklace geometry scale, without mutating the torso model. |
| Crossbow charging pose is overwritten by Fresh Animations | Preserve the selected native two-handed/clamped crossbow pose through the existing NEA bridge, including interpolation, release and cleanup. Re-aim arm pitch during local FirstPerson loading while retaining native charge timing, yaw, pivots and the selected clamp. |
| FirstPerson torso blocks the center when looking straight down | Blend a scale-aware backward body-render offset from 40 to 90 degrees using the actual camera direction. Do not move the camera or change interaction rays. Keep native Better Combat arm visibility; exclude swimming, flight, sleeping, rolling and Sable ragdoll cameras. |
| Accessory log flooding | No per-frame diagnostic logging. A failed supplemental Aether draw uses Accessories' existing throttled renderer-error handling. |
| Config screen unavailable on NeoForge | Native Mods configuration screen and `config/emf-attachments-client.toml`. |
| Offset range too small | Arm positions support -32 to 32 model pixels, including -6; units are shown in the settings. |
| Head slots/cosmetics still render | Filter the actual selected accessory or Curios renderer call, including cosmetic stacks, during the local player's body pass. |
| Third-person conflicts | Require the actual local first-person main camera; exclude other entities, detached cameras, extraction and Iris shadows. Preserve equipment and visibility state. |
| Longer attacks stop early/misalign | Follow Better Combat's real animation layer, including fade-out, instead of a separate swing timer. Keep corrections scoped to the local animation processor. |
| Duplicate weapon during attacks | Coordinate FirstPerson's body/hand choice with PlayerAnimator's competing first-person pass without rewriting either mod's settings. |
| Coordinated body rendering ignores Better Combat’s native arm visibility | Read the actual attack’s cached arm flags, including mirroring and fade-out. Mask only arm/sleeve and copied worn geometry, while preserving weapon attachment transforms and third-person rendering. |
| Player scaling moves the torso away from the camera | Scale FirstPerson's native body offset in all three axes with the vanilla scale attribute before other registered offset handlers run. Version 1.2.0 also corrects the vertical swimming displacement at small and large scales. |
| A head model can occupy a different accessory slot | Apply the head-hiding policy to known physical head models independently of their storage slot, including the Artifacts whoopee cushion. Explicit visible-item overrides still win. |
| FirstPerson's native empty-head lookup affects other rendering contexts | Narrow the original handler to the actual local body pass; preserve the original equipment lookup for other players, third-person cameras, extraction and shadows. |
| Repeated Warden helmet baking reaches EMF's model-creation cutoff | Cache the native Warden helmet model per renderer; resource reload creates a fresh renderer and model. |
| Aether shield effect surrounds the FirstPerson camera and obscures the hand | Follow the parent body's hidden parts in the local body pass, restoring the shield model afterward. The same correction covers Deep Aether's wind shield. |
| Translucent first-person shield surface replaces skin with the world behind it | Restore Iris's normal entity grouping around the injected FirstPerson body, preserving native skin-before-effect submission order. |
| NEA custom bow pose is overwritten by Fresh Animations | Add the selected custom bow animation to the existing EMF/NEA bridge's preservation rules; keep its native cleanup, settings and interpolation. |
| NEA's custom bow pose obscures the bow in the first-person body | Use the native aimed bow pose only during the local FirstPerson body pass. Preserve the selected custom pose for third-person and other players. |
| Shadow and first-person passes share one interpolation history | Keep separate native bridge state for the local body pass. Preserve the bridge's easing and frame timing; clear private state on world/player changes, third-person switching or disabling the integration. |
| Better Combat's horizontal hitbox can miss at an exact cardinal angle | Ignore mathematically zero cross axes, including negative-zero components, in its separating-axis collision check. Preserve nonzero axes and native face-touching behavior. |
| Sable ragdolls draw bridged accessories through both APIs | Record completed Accessories draws within one physical part and skip only their exact Curios bridge duplicates. Preserve separate slots, indexes, item components, players and render passes. |
| Native Tidal Claws pull triggers Ragdoll Reactions' impact heuristic | Recognize an owned, live, pulling native hook tied to the actual held Claws stack. Skip only the generic motion-impact check during that pull; preserve other reaction handlers and resume normal tracking after release. |
| Native ragdoll first-person camera gets a block overlay from the displaced source player's eye | During Sable's opted-in, attached local first-person camera only, sample opaque blocks at the actual camera origin. Retain the overlay when the camera itself is inside opaque terrain and preserve other camera/render contexts. |

The existing Aeronautics goggles, Friends & Foes totems, Deep Aether scarf, Bumblezone flower and EMF hidden-head corrections are retained. Added scarf hiding covers the two Artifacts scarves and Deep Aether's floaty scarf; other necklaces remain visible. Exact-item overrides and configurable slot matching allow additional accessories to use the same policy.

## Configuration

Open **Mods → EMF Attachments Compat → Config**. Settings are client-side and can also be edited in `config/emf-attachments-client.toml`.

- `hiddenSlots` matches slot paths or separator-delimited words, avoiding unrelated namespace or substring matches.

- `hiddenItems` adds exact item IDs; `visibleItems` overrides all hiding rules.

- `hideScarves` controls the three built-in scarf cases independently of `hideHeadAccessories`.

- Combat offsets use model pixels for arms and blocks for body height; rotations use radians.

- `downwardBodyClearance` is the extra render-only distance at 90 degrees in blocks for a normal-size player (default `0.45`). The distance follows player scale and blends to zero at 40 degrees. Set it to `0` to disable this correction independently of the other fixes.

- Additive XYZ arm and look-up translations default to zero, preserving native shoulder and grip positions. Existing explicit configuration is retained.

- Better Combat’s own `isShowingArmsInFirstPerson` setting remains in control during melee attacks. Its native visible-arm animations can intersect the camera during windup; honoring hidden arms does not change those poses.

- Turning off `combatCompatibility` restores the original integrations' render choices without having to restore a rewritten third-party config.

- `animation.firstPersonBowPose` controls the local aimed-bow correction; `animation.separateFirstPersonPoses` controls the independent first-person interpolation history.

- `animation.firstPersonCrossbowPose` controls the local crossbow-loading pitch correction. Turning it off preserves the selected native loading pose, which can sit below the first-person viewport. It does not disable the bridge's preservation of third-person crossbow animations.

## Scope

The integrations support Better Combat, Combat Roll and Not Enough Animations. With EMF installed, the relevant EMF bridges enable the EMF/NEA pose corrections. Without EMF, those integrations stay inactive and the other corrections retain their own mod-presence checks. The replacement respects FirstPerson activation handlers and yields during Combat Roll.

Comments also request other game versions or integrations with Essential, Punchy, Spell Engine, Relics, Werewolf and gun/cinematic mods. Those absent mods are not certified by this build. Vanilla attribute scaling is covered separately from Pehkui-specific scaling. See [TESTING.md](TESTING.md) for exact runtime evidence and limits; historical 1.0.0 results used the now-removed companion and do not establish replacement behavior.

