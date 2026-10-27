# FirstPerson compatibility replacement

Version 1.2.7 extends the FPM x Compat replacement introduced in 1.1.0 for this Minecraft 1.21.1 / NeoForge instance. FirstPerson is optional; installation preserves its current presence or absence. The replacement neither bundles nor calls FPM x Compat, and the installer removes that companion from the instance's mods directory.

## Reported problems and implementation

Reviewed the [CurseForge comments](https://www.curseforge.com/minecraft/mc-mods/first-person-model-compat/comments) and linked [issue tracker](https://github.com/twk0d/fpm-x-compat/issues) on 2026-10-24. Reports are reproduction leads, not proof that every installation has the same fault.

| Report | Replacement behavior |
| --- | --- |
| Sleeping player lies at the wrong angle on a rotated Sable bed | Keep Sable's dedicated bed rotation and suppress its duplicate general player rotation only for a sleeping client player on a live sublevel bed. Skip the matching inverse rotation in name tags so they still face the camera. Ordinary beds, awake players, camera rotation and gameplay stay native. |
| Night Vision Goggles or another head wearable intrudes into the sleeping camera | Apply the existing head/scarf visibility policy during the local sleeping player's first-person world draw, even without FirstPerson. Preserve third-person/other-player and shader-shadow draws, slot visibility and exact-item overrides. This covers the Accessories/Curios paths, not arbitrary custom armor or shield renderers. |
| Native animated hands falsely react to falling/crouching on a moving Sable boat | Keep independent EMF first-person-hand and world/shadow motion histories. Filter only carried deck motion; preserve intentional crouching, jumping and actual landings. |
| Aether gloves are absent from Better Combat's native first-person attack arms | Supplement the filtered Accessories layer with only the exact native Aether glove renderer. Use the native scaled body pose and mirrored arm visibility; retain hidden slots, cosmetics and cloak rules. Applies with FirstPerson absent. |
| Diamond gloves detach while shrunk, also in third person, with an unidentified Simply Swords weapon | The user confirmed on October 27 that the 1.2.6 glove change resolved this symptom in their gameplay. The isolated ordinary-ground tests did not reproduce this separate detachment; distinguish the user-confirmed result from the directly reproduced native attack omission. |
| Fresh Player repeatedly bends while standing or walking on a moving Sable deck | Correct Fresh Player's previous-height and dragged-height animation history for deck movement, without changing physical crouch, movement or network data. Recognize the installed Fresh animation contract and update only when EMF actually evaluates it. |
| Players on Sable sub-levels can become stuck behind Entity Culling's tick suppression; the old broad exemption also rendered hidden players | Exempt only client players attached to a live Sable sub-level from tick suppression. Keep native render culling active, including behind walls. Pending plot positions recover through full Sable ticks; stop the tick exemption after detachment/removal. Server tracking, packet delivery, actual invisibility and ordinary world-player culling are unchanged. |
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

The installed pack includes Better Combat, Combat Roll, Not Enough Animations and their existing EMF bridges. The replacement respects FirstPerson activation handlers and yields during Combat Roll; those existing animation bridges remain installed.

Comments also request other game versions or integrations with Essential, Punchy, Spell Engine, Relics, Werewolf and gun/cinematic mods. Those absent mods are not certified by this build. Vanilla attribute scaling is covered separately from Pehkui-specific scaling. See [TESTING.md](TESTING.md) for exact runtime evidence and limits; historical 1.0.0 results used the now-removed companion and do not establish replacement behavior.

