# Verification record

The production build was tested in an isolated copy of the supplied Minecraft instance, using Minecraft 1.21.1, NeoForge 21.1.264, EMF 3.3.12, ETF 7.2.6, Fresh Animations 1.11.1 and FA Player Extension 1.1. Accessories, its Curios bridge and the installed equipment mods remained enabled. FirstPerson runs used FirstPerson 2.7.3 and FPM x Compat 1.2.1. The copied shader configuration enabled **Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5**.

Production artifact: `emf-attachments-compat-1.21.1-1.0.0.jar`  
SHA-256: `2D4E84878F8323A2E5DAAB8772B9E5A548FA9C401E580FBD792370395953650C`

Exact dependency and resource-pack hashes are recorded in [environment-1.0.0.json](verification/environment-1.0.0.json). The developer harness is a separate jar and is not part of the normal installation.

## Installation

Installed on 2026-10-24 in the supplied CurseForge **Modded Minecraft** instance. The production jar, FirstPerson 2.7.3 and FPM x Compat 1.2.1 are enabled. Each installed file matches the tested binary; hashes are recorded in [installed-files.json](verification/installed-files.json). The original FirstPerson configuration is unchanged, and no developer harness is installed. The change record and original disabled FirstPerson jars are preserved in [.local/installation-backup-20261024-195106](.local/installation-backup-20261024-195106/).

## Coverage and method

Runtime discovery found **327 distinct item IDs in 400 actual item/slot/index combinations**. An additional blue-dyed Bumblezone flower headwear case gives **401 cases**. Each full final sweep rendered all cases in four controlled poses: idle, walking with head turned, crouching with head down, and swimming. This is **1,604 renders per sweep**. Standard and slim player models were tested with FirstPerson installed.

The harness uses separate player clones and the actual installed PlayerRenderer, EMF model parts and equipment layers. Distinct clone UUIDs prevent EMF animation caching from making all four poses identical. Reports record `EMFModelPartVanilla` and differing animated head transforms. Runtime slot validation includes the Curios bridge. Accessories' four armor UI aliases are excluded because they refer to equipment slots already tested; the early baseline's 216 duplicate alias cases are filtered from the exported comparison.

Checks detect render exceptions, non-finite accessory vertices and accessory renderers changing the shared player pose. Targeted assertions compare the goggles' head and the corrected scarf/totem torso anchors with the animated player. Flower checks verify head attachment, default/blue tint, and **144 vertices from the six native flower petals**, detecting the incorrect generic helmet model that pose checks alone would miss. Vanilla armor and separate cape/quiver layers also render in the screenshots, but the accessory vertex counter does not measure those layers.

The FirstPerson probe exercises transformed EMF parts and the real cosmetic-armor lookup. It checks hidden head/face geometry, body geometry, return to third person, independently hidden children, and cosmetic helmet hiding only during the FirstPerson body pass. These are renderer probes; they are separate from the live camera sequence below.

## Results

| Run | Cases / pose renders | Result |
| --- | ---: | --- |
| Baseline, compatibility fixes disabled, FirstPerson enabled | 400 / 1,600 | Reproduced 4/4 goggles anchor failures and 16/16 torso anchor failures. FirstPerson hidden head/face emitted 48 vertices and failed. |
| Final build, FirstPerson absent | 401 / 1,604 | Complete; no discovery/render errors, invalid geometry, parent-pose mutations or targeted assertion failures. |
| Final build, FirstPerson enabled, standard model | 401 / 1,604 | Complete; all sweep checks and FirstPerson probe pass. |
| Final build, FirstPerson enabled, slim model | 401 / 1,604 | Complete; all sweep checks and FirstPerson probe pass. |
| Final rear-view attachments | 31 / 124 | Complete; all checks pass and all 7 pages visually reviewed. Native quiver geometry and body/leg anchors confirmed. |
| Final live FirstPerson camera sequence | 21 stages | All 21 screenshots captured and visually reviewed; head attachment/hiding, camera/body behavior and cape idle/crouch checks pass. |

Each final full sweep passed 4 goggles anchor checks, 16 torso anchor checks, 8 flower anchor checks, 8 flower color checks, 8 native flower geometry checks, and 4 blue tint checks. The passing FirstPerson probe emitted 0 hidden head/face vertices, preserved 48 normal body/third-person vertices and 24 visible-parent vertices with an independently hidden child. Cosmetic head armor was hidden only in the body pass. **Four JUnit regression tests passed.**

All 80 pages of the initial complete corrected sweep were visually reviewed across the team. Reviews covered armor, headwear, gloves and bracelets, footwear, backpacks, wings and other visible accessories, followed by targeted reviews of the final changed renderers and slim-arm models. Final flower screenshots confirm the native petal shape for both default and blue dye. The 7 final rear-view pages cover capes, elytra, backpacks, backtanks, jetpacks and quiver. The initial artificial cape pose was traced to EMF's model initialization delay and resolved by warming the test clones before capture; the final capes follow the player normally.

The quiver's configured `THIGH` mode produces a narrow brown pouch beside the leg. Its separate native layer emitted exactly **64 finite vertices in each of four poses**, with one actual animated body-anchor call and one actual leg-anchor call per pose. All four native draw checks passed. Items using an empty accessory renderer may be intentionally invisible or drawn by another layer; zero accessory vertices alone are not treated as a visual failure.

The final live sequence uses the actual world camera and local player for **21 stages**: looking up/down, crouching, armor/accessory/cosmetic goggles, virtual cosmetic head armor, Create goggles, flower headwear, third-person views, FirstPerson disabled/re-enabled, and cape idle/crouch. All 21 daylight screenshots were reviewed successfully, including cosmetic head armor hidden in first person and visible in third person, native flower geometry, normal cape attachment, and camera/body restoration. Its report records `CAPTURED`; the visual pass comes from reviewing the screenshots, not that status alone. It operates only in the disposable integrated test world and restores saved equipment and camera state.

## Evidence and reproduction

- [Summary CSV](verification/summary.csv) and [summary JSON](verification/summary.json): canonical baseline and final sweep counts/checks.
- [Live summary](verification/live-summary.json): camera captures and their report paths.
- [Raw reports and labeled screenshots](.local/client/emfattachments-tests/): each run's `report.json`, contact sheets, or `live-report.json` and camera images.
- [README reproduction steps](README.md#reproduce-the-runtime-tests): isolated launch, trigger format and developer harness details. Run `python scripts/summarize_tests.py` to refresh the exports.

## Limits

Coverage is exhaustive for the runtime-discovered default wearable item/slot combinations in this installed configuration, with one explicit blue-dye variant. It is not exhaustive for every item component/NBT value, simultaneous equipment combination, skin texture, animation transition, mod configuration or shader. Four controlled poses and selected real camera stages do not cover all movement, combat, flight or multiplayer/server conditions. No existing user world or multiplayer server was used for these tests.
