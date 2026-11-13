# EMF Attachments Compat

A compatibility mod for Minecraft 1.21.1 / NeoForge, built against the installed EMF 3.3.12 and Fresh Animations Player Extension 1.1 setup. Most integrations affect client rendering; the optional Cataclysm grapple correction runs with the server's native motion handling.

Version **1.2.13** restores Entity Culling's reduced client ticks for safely attached remote players on Sable 2.0.6 with Entity Culling 1.12.0. It keeps Sable's native attachment and interpolation maintenance while hidden players skip their full tick. Attachment transitions and unsupported versions retain the full-tick fallback; render culling stays native. Four copied-client benchmark runs measured **2.77–3.37% higher FPS with hidden players**, with visible-scene means within 0.8% of the full-tick control. Functional checks covered moving and rotating decks, visible reentry, detachment and sublevel removal. See the [1.2.13 verification report](verification/sable-reduced-1.2.13/REPORT.md) for evidence and offline-test limitations.

The reproduced 1.2.11 startup crash came from Entity Culling moving its helper class. The updated target is included here; 1.2.13 completed testing with Sable Player Ragdoll 0.8.1, Ragdoll Reactions 0.8.0 and RagRevival present. The supplied modpack was read-only throughout testing.

Version **1.2.12** removes work already handled by EMF Compat Core. With Core **2.4.0+**, our Sable deck-motion calculations are skipped. A small Fresh Player history guard remains because tests reproduced a landing blip from a one-float rounding step in Core's corrected height. With Core **2.3.2+** and its crouch normalizer enabled, our extra first-person crouched-combat height offset is skipped. The remaining combat rotation, gun-pose, accessory and interpolation corrections still handle behavior that Core does not replace. See the [upstream update audit](COMPATIBILITY.md#emf-compat-update-audit--2026-11-09).

The full Sable correction remains available with older or absent Core. On Core 2.4.0+, its **Sable: steady height on sub-levels** setting controls the feature; disabling it also clears our small precision guard and does not reactivate the full fallback. The combat-height fallback remains available when Core is absent, older than 2.3.2, or its crouch normalizer is disabled.

The six-shooter changes from **1.2.10** are retained: the gun hand fires and reloads in both views, and with FirstPerson installed the free arm and its accessory can stay visible during both actions. Iron's native firing, recoil and reload timing remains intact.

EMF and ETF remain optional, as introduced in 1.2.8. Independent accessory, FirstPerson, Better Combat and Sable corrections remain available when both are absent; EMF-specific animation integrations activate only when EMF is installed. If installed, EMF must be 3.3.12 or newer and still requires its own compatible ETF version. Other mods' EMF dependencies are unchanged.

The sleeping-player alignment and native first-person sleeping accessory fixes from 1.2.7 are retained, along with the earlier native-hand, Aether glove and Entity Culling corrections. [TESTING.md](TESTING.md) separates current checks from historical equipment and weapon replays. On October 27 the user also confirmed that 1.2.6 resolved their idle/third-person shrinking glove symptom; the isolated tests did not reproduce that separate symptom. These tests do not certify every possible scenario. FPM x Compat is not used.

## Corrections

- **Iron's Arms 'n Artifice third-person poses:** retains the gun arm transforms through EMF evaluation, including reload progress and dominant-hand selection. The six-shooter uses the holding arm throughout. Rifles use both arms, and the flintlock and blackpowder revolver use both during reload. The arms follow the animated torso translation.
- **Iron's native first-person accessories:** invokes each equipped accessory's native first-person renderer inside Iron's actual animated hand draw. Preserves hidden slots, cosmetic selection, physical left/right hand choice and native one-/two-hand occupancy. Artifacts and Deep Aether Skyjade gloves receive the bone-local pose after their own model resets; native Aether gloves use the supplied arm model. Head and torso accessories keep their own native first-person visibility policies.
- **Iron's guns with FirstPerson installed:** registers gun items in FirstPerson's native hands cache, retaining Iron's first-person gun/reload renderer while FirstPerson renders the body. Registration repeats when FirstPerson reloads its settings; no configuration file is rewritten. A scoped geometry mask hides only body arms replaced by native gun hands, including their copied gloves through EMF and straight-down views. A one-handed pistol keeps the free body arm and its accessory; native two-handed reloads take over both arms, and the free arm returns afterward. Works independently of EMF.
- **Flexible headwear during ragdolls:** with Sable Player Ragdoll 0.7.5+, routes recognized head models by the selected item rather than its storage slot. A Whoopee Cushion in an anklet slot renders once on the head instead of on both legs and the torso. Functional/cosmetic selection, hidden slots, separate equipped stacks and ordinary non-head routing are retained.
- **Aether and Skyjade gloves during ragdolls:** canonical paired-arm slot routing prevents the Curios bridge from drawing an extra pair on the torso. Each physical arm draws only its own glove geometry, including trim/glint. Shared model transforms and visibility are untouched, so normal rendering resumes after recovery.
- **Sleeping on rotated Sable beds:** prevents Sable 2.0.6 from applying the bed sublevel's orientation twice to a sleeping player. Keeps Sable's dedicated sleeping transform, including the bed-direction translation, and skips only its duplicate general player rotation. Keeps the matching name tag facing the camera by skipping the inverse of that removed rotation. Ordinary beds, awake players, camera orientation, physical movement and sleep gameplay retain their native handling.
- **Wearables in the sleeping camera:** applies the existing configurable head/scarf visibility rules to the local sleeping player's native first-person world draw. Covers Accessories, bridged Curios and native Curios, including the selected cosmetic stack and known head models worn in flexible slots. FirstPerson is optional. Other players, third-person, inventory previews, detached/extraction cameras and shader shadows stay outside this hiding scope; equipped items and persistent visibility settings are not changed.
- **Aether gloves in native first-person combat:** draws the native Aether glove renderer in PlayerAnimator's first-person attack pass, where the ordinary Accessories layer is filtered out. Preserves the actual native arm visibility, hidden slots, cosmetic overrides and Aether invisibility cloak. Uses the existing scaled body matrix and glove base/trim/glint geometry; does not apply another size multiplier. This integration requires Aether, Accessories, Better Combat, PlayerAnimator and EMF, with FirstPerson absent. Custom glove renderer subclasses such as Skyjade are outside this correction.
- **Independent animated-hand history on Sable decks:** maintains separate hand and world/shadow histories for the legacy correction or Core precision guard. Both histories reset after leaving support. Storage is allocated lazily once per player context, not once per frame; the legacy matrix/vector storage is never allocated by the Core precision path.
- **Fresh Player animation on Sable decks:** before Core 2.4.0 or without Core, removes carried deck movement from Fresh's history, including the existing 0.002-block grounded contact tolerance. With Core 2.4.0+, performs no deck transforms and only aligns one downward adjacent float step in the already-corrected height, capped at 0.0001 blocks. This guard requires continuous grounded contact on the same support and preserves drag lag. Airborne movement, first contact, larger height changes and native landing timers remain untouched. Both paths apply only to recognized Fresh Player handlers and supported, unseated players at actual EMF evaluation boundaries.
- **Player visibility and ticking on Sable decks with Entity Culling:** on the verified Sable 2.0.6 / Entity Culling 1.12.0 pair, safely attached remote players use native reduced ticks with Sable maintenance immediately before native interpolation. Pending attachment, changed or removed support, sleep, death and riding retain full ticks as needed; unverified version pairs keep the prior full-tick protection. Hidden player/accessory rendering, ordinary-world tick culling, native frustum/depth tests and actual invisibility retain their native behavior. The focused 1.2.13 checks cover moving and rotating decks, natural walls, visible reentry, stale-cull controls, detachment and removal.
- **Artifacts on animated native first-person hands:** copies the actual just-rendered EMF arm pose into Artifacts' glove model after its native reset, then restores that model. Covers Onion Ring, Digging Claws, Feral Claws, Power Glove, Fire Gauntlet, Pocket Piston, Vampiric Glove, Golden Hook, Pickaxe Heater and Withered Bracelet. Requires EMF's first-person hand animation to be enabled; it does not enable the option for the user. FirstPerson is optional. Third-person and ragdoll paths remain separate.
- **Inactive FirstPerson arm masks:** reuses empty scopes and native visibility results, and allocates model bookkeeping only when masking is needed. Nested active scopes and restoration remain intact. This reduces allocations with FirstPerson installed; it is not evidence that FirstPerson-absent multiplayer FPS drops are fixed.
- **Face accessories during Ragdoll Revival:** uses the installed Accessories head-slot classification for the Curios bridge, preventing an extra torso copy of the Whoopee Cushion. Stored ragdoll draws consult the actual online owner's Accessories visibility flag and honor the renderer's native visibility policy. Cosmetic replacements suppress their functional item's fallback only after the selected cosmetic successfully renders. Missing/offline owners retain native behavior because Sable's stored item snapshot contains no visibility flags.
- **Steep downward views:** smoothly moves the local rendered body farther behind the actual camera between 40 and 90 degrees, following player scale and free-look. The camera, interaction rays and Better Combat arm setting are unchanged. Swimming, flight, sleep, rolls and Sable ragdoll cameras retain their existing positioning. `downwardBodyClearance` controls the maximum distance; zero disables it.
- **Artifacts necklaces while running:** keeps the copied torso translation at full size while retaining the necklace's authored geometry scale, so the pendant follows Fresh Animations' leaning body.
- **Ragdoll claws and gloves:** prevents Artifacts and Cataclysm arm models from re-enabling the opposite arm during a physical Sable arm draw. Separate hand items and normal rendering remain independent.
- **Crossbow charging and loaded holding:** preserves NEA's selected native two-handed and clamped crossbow poses through Fresh Animations, including early charging frames. During local FirstPerson charging, arm pitch follows the camera and keeps the pulling hand within the aiming pose, retaining native charge timing, yaw and arm pivots. Firing and cancellation use native Minecraft behavior. `animation.firstPersonCrossbowPose` disables the local pitch correction independently.
- **Aeronautics goggles in Curios or the Accessories bridge:** restores the animated head transform after the original renderer overwrites it with vanilla animation. Includes position, rotation, and scale.
- **Friends & Foes wearable totems:** follows the player's animated torso instead of applying a fixed vanilla crouch approximation.
- **Deep Aether floaty scarf:** anchors its custom scarf geometry to the animated torso.
- **Bumblezone flower headwear:** supplies the missing accessory-slot visual using native flower geometry, preserving the equipped flower's dye and enchantment glint.
- **FirstPerson:** keeps EMF head/face and other parts hidden during the first-person body pass when FirstPerson marks them hidden. Third-person rendering remains enabled.
- **FirstPerson with cosmetic armor:** prevents Accessories' cosmetic helmet lookup from overriding FirstPerson's hidden head slot during its body render. Inventory contents and third-person cosmetics are preserved.
- **FirstPerson accessory visibility:** hides configured head slots and camera-obstructing scarves during the local player's body pass. Ordinary and cosmetic Accessories/Curios rendering use the same rules. Other players, third-person cameras, camera extraction and shader shadows remain outside that scope.
- **Better Combat:** coordinates ownership of first-person arm/item rendering through the complete attack and fade, including the reported stab/slam paths. The actual native arm/sleeve visibility flags, mirroring and fade are preserved, including copied armor/glove geometry. Weapon attachment transforms remain intact, and user animation settings are not rewritten.
- **Player scaling:** scales FirstPerson's original body translation, including its vertical swimming correction, with the vanilla player scale attribute. Later external offset handlers retain their own units.
- **Head-slot scope:** restricts FirstPerson's native hidden-head lookup to the local body pass, preserving helmets for other players, detached cameras and extraction renders.
- **Deeper and Darker Warden helmet:** reuses its baked model for the renderer lifetime, avoiding EMF's repeated-model creation cutoff. Resource reload creates a fresh renderer and model.
- **Flexible accessory slots:** identifies known head models by item ID as well as slot, including Artifacts' whoopee cushion when equipped outside the head slot.
- **Aether and Deep Aether shield effects:** makes shield shells follow the parent body's hidden parts during the local FirstPerson pass, preserving third-person, other-player and shadow rendering.
- **Iris translucent rendering:** gives the injected FirstPerson body Iris's normal entity draw-order group, so skin renders before translucent shields. Native shaders, textures and effect geometry are retained.
- **NEA custom bow animation:** lets the installed EMF/NEA bridge preserve its selected custom bow pose through Fresh Animations, without changing animation settings or shoulder positions. A scoped native aimed pose keeps the bow visible in the local FirstPerson body; a separate native interpolation cache prevents shadow passes from overwriting it.
- **Sable ragdoll accessories:** avoids drawing the same bridged accessory twice through Accessories and Curios. Matching is limited to the same player, canonical slot, index and complete item stack within one physical part draw; independent accessories and later renders are preserved.
- **Cataclysm Tidal Claws with Ragdoll Reactions:** excludes the exact active native grappling pull from the generic motion-impact heuristic. The original fall, hit and explosion handlers remain active; ordinary motion tracking resumes after release. Gameplay verification uses the integrated server, not a separate multiplayer server.
- **Native ragdoll camera overlays:** samples blocking geometry around the actual aligned first-person camera instead of the displaced seat player's eye. An opaque block at the camera still produces the normal overlay; ordinary, third-person and other-player views retain native behavior.

Each optional integration is selected only if its required mods are present. This mod adds no equipment, networking, or saved data. Keep the original mods installed. The linked [Aeronautics Curios mod](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics-x-curios-api-compat) is still responsible for equip slots, overlays, and typewriter behavior.

The historical [1.2.5 culling comparison](verification/culling-1.2.5/performance/REPORT.md) used six fresh no-FirstPerson runs with equipped player crowds and moving Sable decks. Its hidden-player scene recovered from 128.39 FPS in 1.2.4 to 240.87 FPS, versus 240.96 FPS with this mod removed, while retaining full player ticks. The [1.2.13 comparison](verification/sable-reduced-1.2.13/REPORT.md) measures the additional reduced-tick change against that full-tick behavior with the updated pack; results from the different fixtures should not be compared directly.

## Build

Ongoing development uses the [`Dev` branch](https://github.com/BigUziHert/Compat-Mod/tree/Dev).

Use Java 21:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat build testModJar
```

The build reads compile-only mod dependencies from the existing instance's `mods` folder. For another instance, supply `-PinstanceMods=C:/path/to/mods`. If FirstPerson is absent there, supply `-PfirstPersonJar=C:/path/to/firstperson.jar` for compilation and tests only. Those installed mod jars are not bundled. MixinSquared 0.3.7 is included as a nested dependency for the narrow FirstPerson and Entity Culling handler integrations.

The production artifact is `build/libs/emf-attachments-compat-1.21.1-1.2.13.jar`. The separate `test-harness` jar is for development only and must not be installed for ordinary play. Sable's nested companion API is extracted for compilation only and is not bundled in the production artifact.

## Reproduce the runtime tests

For the optional-dependency smoke test, `scripts/check_optional_runtime.py` prepares a separate client and a small probe without EMF API references. It uses an existing disposable `AttachmentTest` world, equips native accessories, captures four first/third-person views and stops the client. This checks loading and rendering; it does not certify every gameplay or visual-alignment scenario. Use a fresh label for each preparation:

```powershell
python scripts/check_optional_runtime.py prepare --label absent-check
python scripts/check_optional_runtime.py launch --label absent-check
# After the client exits:
python scripts/check_optional_runtime.py collect --label absent-check
```

Add `--emf` to `prepare` to include EMF, ETF and the installed EMF bridges in that disposable copy, or `--firstperson C:/path/to/firstperson.jar` to include FirstPerson. The older full developer harness below targets the EMF animation setup and is not the absence test.

`scripts/test_runtime.py` creates an isolated client under `.local/client`, using copies of the installed mods/config/resourcepacks/shaders and existing local Minecraft/NeoForge launcher libraries. It uses an offline development identity and never connects to the user's multiplayer server. Existing user saves and account credentials are not copied.

```powershell
python scripts/test_runtime.py
python scripts/test_runtime.py --firstperson
```

Run only one client per test directory. Create a creative superflat world named `AttachmentTest` on the first launch. Later launches open that disposable world automatically. The harness opens after the world loads; report JSON and labeled screenshots go to `.local/client/emfattachments-tests/`. A separate `--runtime .local/without-firstperson/.local/client` directory permits comparison with FirstPerson physically absent. `--no-auto-sweep` waits for an explicit trigger. Dynamic FPS is disabled only in these disposable copies so background windows retain normal test cadence.

To name or filter a sweep, write `emfattachments-test-start.txt` in the test client directory before launching, with a run label on the first line, an optional item-ID regular expression on the second line, `default` or `slim` on the third, and optionally `back` on the fourth. Escape the completed sweep screen before starting another. The harness tests independent player clones without changing the real player's equipment. It enumerates runtime equipment slots and Accessories slots, including the Curios bridge, and renders controlled poses through the installed renderers.

For the live FirstPerson sequence, close the sweep screen and write a label to `emfattachments-live-firstperson.txt`. This developer-only sequence operates in the isolated integrated test world and restores its saved equipment and camera state when finished. Run `python scripts/summarize_tests.py` to export the verification records.

For native attack captures, write a label to `emfattachments-live-combat.txt` while no sweep or live sequence is active. It uses the installed weapon/combo definitions and records 36 camera views plus body/weapon renderer counts across windup, strike, recovery and completion. The FirstPerson sequence also checks active food/drink, shield and spyglass use, including temporary NEA eat/drink settings; those settings are restored without saving.

Renderer execution and finite geometry checks are broader than alignment assertions. Specific anchor assertions target corrected renderers. Screenshots need visual review; the sweep does not by itself prove every live movement, skin, shader, item component, or first-person camera condition.

See [TESTING.md](TESTING.md) for the current verification record and [TESTING-1.0.0.md](TESTING-1.0.0.md) for the historical build. The 1.1.0 replacement runs use `replacement-` labels; expanded 1.2.0 runs use `expanded-` labels and immutable artifact manifests. [COMPATIBILITY.md](COMPATIBILITY.md) maps reported issues to this release's scope.

Detailed evidence referenced under `verification/` is retained in the development workspace and excluded from this public source repository. Those local links require the original evidence files. Runtime copies, recordings, logs, generated builds and superseded staging files are also excluded; production source, unit tests, the developer harness and analysis scripts are included.

The 1.1.0 replacement was tested with FPM x Compat absent: 327 wearable item IDs, 400 equipment/slot combinations plus a dye variant, standard/slim models, and FirstPerson enabled/disabled. Its historical tests cover 224 local/remote visibility scenarios, 29 real camera/use stages and 36 native attack views. The expanded 1.2.0 verification records live gameplay separately from controlled rendering; see the per-case reports and coverage limits in the verification record.

## Configuration

Open the mod's configuration from NeoForge's **Mods** screen. Settings include head-slot/scarf hiding, exact hidden and visible item lists, combat alignment and player-scale correction. Visible item overrides take precedence over hiding rules. Additive arm translations default to zero to preserve native shoulder and grip positions; existing explicit settings are retained. Position settings accept values below -4, including -6. The mod performs no per-frame accessory logging and does not edit other mods' configuration files.

## FirstPerson

Use [FirstPerson 2.7.3](https://modrinth.com/mod/first-person-model/version/eaj8cYpD) with this compatibility mod. The replacement integrations introduced in 1.1.0 do not require or load FPM x Compat. The existing FirstPerson jar was checked against its official release hash; FirstPerson configuration is preserved.

`scripts/install.ps1` installs the production jar, preserves FirstPerson's current installed/disabled state and removes the replaced companion from the active mods directory. It records changes and backs up replaced files under `.local/installation-backup-*`. The optional `-EnableFirstPerson` switch explicitly enables an existing verified disabled FirstPerson jar.
