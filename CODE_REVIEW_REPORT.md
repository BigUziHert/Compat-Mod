# Code review report — EMF Attachments Compat (`emfattachments` 1.2.20-dev)

| | |
|---|---|
| Repository / branch | `BigUziHert/Compat-Mod`, branch `Dev` |
| Commit reviewed | `6284bab` ("Fix moving elevator rider handoffs and invisible player equipment poses") |
| Target | Minecraft 1.21.1, NeoForge 21.1.264, Java 21 |
| Review date | 2026-11-23 |
| Purpose of this document | Findings and proposed fixes, written so a second reviewer can verify and test each one independently |
| Status | **Draft.** Two final verification passes are still running. One disputed claim (Artifacts gloves on a left-handed player's ragdoll) is deliberately left out until its re-check finishes |

No code was changed. Every finding below is a proposal.

---

## 1. How to read this report

**What was done.** Three rounds of review by independent reviewers, each followed by an adversarial pass whose only job was to refute the findings:

1. Sixteen subsystem and cross-cutting reviews of `src/main`, checked against the real bytecode of the documented versions of every target mod (36 reference jars downloaded from Modrinth, inspected with `javap` only, never executed).
2. A cross-version sweep: every mixin target and every directly linked third-party member was checked against other released 1.21.1 NeoForge versions of each target mod (172 mod-version combinations).
3. A second discovery round with different lenses: fuzzing the pure maths against the compiled classes, state-machine enumeration of the elevator code, mixin-versus-mixin interplay across about 1,900 third-party mixin classes, unusual players and inputs, independent second reads, the test harness and the dev scripts.

The project was compiled in a scratch copy against the real jars; **210 unit tests ran, 209 passed, 1 was skipped** (`EntityCullingTickContractTest.previous1113TickCallbackStillHasExactlyOneMatchingRequiredInvocation`, because the optional Entity Culling 1.11.3 fixture jar was absent).

**What was not done.** Nothing was run in a live game. Every behavioural claim comes from source, bytecode, unit tests and offline models. Each finding says what remains unverified. Section 7 lists coverage limits.

**Severity.**

| Level | Meaning |
|---|---|
| High | Crash, in a configuration a user can plausibly have |
| Medium | Wrong behaviour visible in ordinary play in a supported configuration |
| Low | Narrow trigger, cosmetic effect, or tooling-only |

**Verification status** is stated per finding: who confirmed it and how.

---

## 2. Summary

The production code is in good shape for the exact mod versions it documents. All 73 mixins match their targets in those versions, the arm/hand/slot mappings check out, scopes and ThreadLocals are restored on every exit path, and fuzzing the geometry and landing maths (200,000 cases each) found no error. Every target mod's **current latest** 1.21.1 NeoForge release also works.

The real problems are:

| ID | Severity | Finding | Status |
|---|---|---|---|
| P-01 | High | No minimum-version checks for optional mods: seven mods have older, still-installable versions that crash the game | Confirmed against the jars |
| P-02 | Medium | Holding jump in a moving Sable-mounted elevator: every hop after the first reuses the first hop's decayed velocity | Confirmed by two independent verifiers |
| P-03 | Medium | Landing on a cabin other than the launch cabin applies the ship's motion twice | Confirmed by two independent verifiers |
| P-04 | Medium (niche) | Spectating a player in first person with FirstPerson: the spectated player's helmet is no longer hidden | Confirmed by one verifier and by reading the code |
| P-05 | Low | Pausing singleplayer during a deck-to-cabin jump drops the launch record, so the landing double-carries | Confirmed |
| P-06 | Low | Not Enough Animations smoothing can replace the first-person bow/crossbow correction under shader shadows when EMF is not animating the player | Partly confirmed (narrower than first reported) |
| P-07 | Low (latent) | `missingCarry` does not discount an active airborne release | Code gap confirmed; no in-game trigger demonstrated |
| P-08 | Low | Reduced-tick version gate admits pre-release builds (`2.0.7-beta`, `1.12.3-beta`) | Confirmed by running the comparator |
| P-09 | Low | Left-handed player: an Artifacts or Cataclysm glove equipped *after* the ragdoll formed is not drawn | Narrowed from a broader claim that was refuted; key link confirmed in bytecode |

Section 4 lists hardening notes that are not bugs today. Section 5 lists 24 defects in the test harness and dev scripts, several of which can produce a false "verified" record. Section 6 lists what was checked and found sound, and what was refuted.

---

## 3. Production findings

### P-01 (High) — No minimum-version checks for optional mods

**Where.** `src/main/java/dev/caleb/emfattachments/compat/OptionalMixinPlugin.java` (`shouldApplyMixin`, lines 34–218) and `src/main/resources/META-INF/neoforge.mods.toml`.

**What is wrong.** Almost every gate is `modPresent("...")` with no version bound, and `neoforge.mods.toml` declares a version range only for EMF. The mixin config is `"required": true` with `defaultRequire: 1`. The helper classes link directly against third-party members that exist only in newer releases, and a few injectors target methods that were renamed. So a user with an older version of a target mod gets one of:

- a startup crash (an applied injector finds no target), or
- a `NoSuchMethodError` / `NoSuchFieldError` / `NoClassDefFoundError` in the middle of rendering or ticking.

The README recommends specific versions but nothing enforces them.

**Affected versions.** Each row was confirmed by inspecting the named jars (`unzip -l`, `javap -p`). "First good" is the oldest version in which every referenced member exists.

| Mod (id) | Versions that break | First good | Failure | Reference in this mod | Gate (line) |
|---|---|---|---|---|---|
| FirstPerson (`firstperson`) | 2.4.9, 2.5.0, 2.6.0, 2.6.3, 2.7.0, 2.7.1, 2.7.2 (every older build checked) | 2.7.3 | `NoSuchMethodError: FirstPersonModelCore.isRenderingPlayerRaw()` on the first first-person frame with the body visible. `NoSuchFieldError: cameraEntityExtract` when sleeping in first person with Accessories or Curios | `client/BodyRenderScope.java:15-16`, `client/SleepingBodyRenderScope.java:50` | every `firstperson` gate, e.g. 196–197 |
| Sable (`sable`) | 1.0.4, 1.0.5, 1.0.6 | 1.1.1 | `NoSuchMethodError: ActiveSableCompanion.getTrackingSubLevel` / `getVehicleSubLevel`. With Entity Culling this fires in the client entity tick as soon as another player is in range ("Ticking entity" crash) | `client/SablePlayerOcclusion.java:19,23`, `mixin/SableFreshAnimationMixin.java:50` | 75–76, 97–104 |
| Not Enough Animations (`notenoughanimations`) | 1.10.3.1, 1.10.4.1 (both are 1.21.1 builds) | 1.10.5 | `NoClassDefFoundError: ...animations/hands/ClampCrossbowAnimations` while rendering a player whose NEA animation is not on the bridge's pause list (ladder, swimming, elytra, spyglass) | `mixin/NeaBowPoseMixin.java:24`, `mixin/FirstPersonNeaCrossbowPoseMixin.java:22,38` | 146–155 |
| Accessories (`accessories`) | 1.1.0-beta.16 to beta.31: startup crash when The Bumblezone is installed; beta.16 to beta.33: link errors at render | 1.1.0-beta.34 | Before beta.32 `AccessoriesRendererRegistry.getRenderer(Item)` was named `getRender`, so the `FlowerHeadwearRegistryMixin` injector matches nothing. `AccessoryRenderer.isEmpty()` first appears in beta.34. `getRenderer(ItemStack)` and `AccessoryRendererErrorCache` first appear in beta.32 | `mixin/FlowerHeadwearRegistryMixin.java:17,21`, `client/ArtificeFirstPersonAccessories.java:62-63,73`, `client/AetherNativeAttackGloves.java:57,69` | 207–208, 51–52, 120–124 |
| Iron's Arms 'n Artifice (`irons_artifice`) | 1.21.1-1.0.1, 1.0.1.1, 1.1.0 | 1.21.1-1.1.1 | `NoClassDefFoundError: io/redspace/irons_artifice/client/gun/FirstPersonArmsModel` on the first frame holding a gun in first person, even with no accessory equipped | `client/ArtificeFirstPersonAccessories.java:39` | 51–52 |
| Sable Player Ragdoll (`sable_player_ragdoll`) | 0.6.0, 0.6.5: startup crash; 0.6.0 to 0.6.7: link error | 0.6.9 | `AccessoriesRenderHelper.renderFromStored` and the Curios lambdas do not exist before 0.6.7, so the dedup mixins match nothing. `RagdollClientConfig` and `RagdollCameraHelper.isFirstPersonAligned` first appear in 0.6.9 | `mixin/RagdollAccessoriesDedupMixin.java:19`, `mixin/RagdollCuriosDedupMixin.java:31`, `client/RagdollBlockOverlayOrigin.java:25` | 163–167, 160–161 |
| Ragdoll Reactions (`ragdoll_reactions`) | 0.1.0, 0.2.0, 0.3.0, 0.4.0 | 0.6.0 | `NoSuchMethodError: ImpactReactionHandler.onPlayerDisplaced` on the **server thread** during a Tidal Claws pull. This mixin is in the common list, so dedicated servers are affected too | `mixin/TidalGrappleImpactMixin.java:20` | 139–142 |
| Artifacts (`artifacts`) | 12.x (12.1.5 checked) | 13.0.0 | No crash. The string target of `ArtifactsFirstPersonArmSourceMixin` was renamed, so the "Artifacts gloves follow animated hands" feature silently does nothing | `mixin/ArtifactsFirstPersonArmSourceMixin.java:17` | 126–128 |

Two aggravating details:

- **Aether bundles an old Accessories.** Aether 1.5.1 to 1.5.6 ship Accessories 1.1.0-beta.16 as a jar-in-jar with range `[beta.16,)`. A pack with one of those Aether versions and no newer standalone Accessories runs beta.16, which hits the Accessories row above. Aether 1.5.7 and later bundle beta.34 or newer.
- **FirstPerson 2.7.3 is recent.** It was released on 2026-09-27, so 2.7.2 is a plausible installed version, and there the crash is on the first rendered frame of ordinary play.

**Proposed fix.** Two layers; I would do both.

1. Declare optional dependency ranges in `neoforge.mods.toml`, exactly as is already done for EMF. NeoForge then refuses to start with a clear message instead of crashing later:

   ```toml
   [[dependencies.emfattachments]]
   modId="firstperson"
   type="optional"
   versionRange="[2.7.3,)"
   ordering="NONE"
   side="CLIENT"
   ```

   Repeat for `sable` `[1.1.1,)`, `notenoughanimations` `[1.10.5,)`, `accessories` `[1.1.0-beta.34,)`, `irons_artifice` `[1.21.1-1.1.1,)`, `sable_player_ragdoll` `[0.6.9,)` (all `side="CLIENT"`), and `ragdoll_reactions` `[0.6.0,)` with `side="BOTH"` because that mixin runs on servers.

2. If refusing to load is too strict for some mods, bound the gates instead so the feature switches off silently. `OptionalMixinPlugin` already has the `versionAtLeast` predicate (used at line 49). For example:

   ```java
   // line 139-142
   return modPresent("cataclysm") && modPresent("ragdoll_reactions")
           && versionAtLeast.test("ragdoll_reactions", "0.6.0");
   ```

   Note that a gate alone is not enough for FirstPerson, Accessories and Sable: their references are reached from many mixins, so every gate that leads to `BodyRenderScope`, `SleepingBodyRenderScope$FirstPersonExtraction`, `ArtificeFirstPersonAccessories`, `AetherNativeAttackGloves`, `SablePlayerOcclusion` and `SableFreshAnimationMixin` needs the bound. The dependency range in layer 1 covers all of them at once.

   For Artifacts 12.x, either add the old class as a second string target (`artifacts.integration.impl.accessories.AccessoriesClientIntegration$ArtifactAccessoryRenderer`) or document 13.0.0 as the minimum.

I ran the proposed bounds through the same comparator the loader uses (`DefaultArtifactVersion` and Maven `VersionRange`) with the real version strings, including `1.1.0-beta.33+1.21.1`, `1.21.1-1.1.0` and `1.10.4.1`. Every broken version is rejected and every good version is accepted.

**How to test.**

- *Static check per row.* Download the two versions from Modrinth and compare, for example:
  `javap -p -cp firstperson-neoforge-2.7.2-mc1.21.1.jar dev.tr7zw.firstperson.FirstPersonModelCore | grep -E "isRenderingPlayerRaw|cameraEntityExtract"` prints nothing; the same command on 2.7.3 prints both members.
- *Reproduce the crash.* Install this mod with FirstPerson 2.7.2 only, join a world, stay in first person: the client crashes with `NoSuchMethodError` from `BodyRenderScope.isLocalBody`.
- *After the fix.* The same setup shows NeoForge's dependency error screen naming FirstPerson and the required range. With 2.7.3 nothing changes.
- *Unit tests.* Add cases beside the existing gate tests (they already use the two-argument `OptionalMixinPlugin` constructor) asserting that each bounded gate returns false for the broken version string and true for the first good one.

**Verification status.** The FirstPerson and Sable rows were reproduced with a link-check script I wrote (it lists every external member the compiled production classes reference and checks them against a jar). All other rows were reported by a sweep agent and then spot-checked by me against the downloaded jars. Not run in game.

---

### P-02 (Medium) — Holding jump in a moving Sable-mounted elevator: later hops fall further and further behind

**Where.** `src/main/java/dev/caleb/emfattachments/client/SableElevatorRiderCarry.java`: `contact` lines 52–56, `removeOverlappingCarrierMotion` line 105, `beforeTravel` lines 137–159, `missingCarry` lines 271 and 283–284, `canRide` lines 435–437.

**What is wrong.** `canRide` is false while `input.jumping` is held. That is intended for the jump itself, but it also blocks the *landing* from being registered:

1. On the first launch tick, `beforeTravel` seeds Sable's inherited velocity with the carrier step and creates an `AirborneCarry` (lines 157–179). Later in the same tick `missingCarry` finds `canRide` false, leaves `floorY` as NaN and calls `clear()` (line 284), which drops `supportedPlayer` and `supportingCabin`.
2. When the rider lands with the key still held, `contact()` returns at lines 52–55 without re-registering support or refreshing `contactTick`.
3. `removeOverlappingCarrierMotion` subtracts the overlap for that tick (line 111) but only consumes the release when `canRide` is true (line 105), so the old release stays alive.
4. Vanilla re-jumps on the next tick (`LivingEntity.aiStep`: `onGround && noJumpDelay == 0`; the 10-tick delay has expired during a 12-tick hop).
5. `beforeTravel` sees the old release with non-zero inherited velocity, refreshes it and returns (lines 138–152). A fresh launch is impossible anyway: line 157 needs `supportedPlayer == player` and `tickCount - contactTick == 1`, and line 174 refuses when inherited velocity is non-zero.

So every hop after the first is driven by the first hop's velocity, still decaying by Sable's 0.99 per tick, while the cabin keeps its full speed.

**Failure scenario.** A Sable ship carries a Create elevator cabin at about 0.83 blocks/tick (the roughly 999 blocks/min fixture in TESTING.md). The rider stands in the cabin and holds Space. A per-tick numeric model in which every rule cites the line it models gives:

| | Hop 1 | Hop 2 | Hop 3 | Hop 4 |
|---|---|---|---|---|
| Held Space: blocks lost relative to the cabin | about 0.5 | about 1.7 | about 2.7 | about 3.6 |
| Tapped jumps | about 0.5 | about 0.5 | about 0.5 | about 0.5 |

The first hop's drift is by design (Sable's airborne drag; TESTING.md records it). The compounding is not: after two or three held hops the rider is pinned against the rear wall of a closed cabin or slides off an open platform. Releasing Space for one grounded tick restores normal behaviour.

**Proposed fix.** Two changes, both needed:

1. In `contact()`, treat a landing with the key held as a real floor contact:

   ```java
   if (!canRide(player)) {
       boolean jumpLanding = mayInherit(player) && !player.getAbilities().flying
               && player.input.jumping && player.getDeltaMovement().y <= 0;
       if (!jumpLanding) {
           if (supportedPlayer.get() == player && supportingCabin.get() == cabin) carriedTick = player.tickCount;
           return;
       }
   }
   // fall through to the existing support switch and contactTick/carriedTick update
   ```

   The `y <= 0` condition stops this from firing on the launch tick while the rider is still rising.

2. In `beforeTravel`, when a fresh contact from the previous tick is followed by a real jump, validate the new launch step first and only then consume the stale release and relaunch. Factor the checks at lines 157–172 into a helper that returns the step or null; if it returns a step, call `consumeHorizontalCredit(player, release.motion, 1)`, set `airborneCarry = null`, and continue into the normal launch code.

Do **not** apply only "always consume the release on landing": without re-registered support the next hop then gets no carrier velocity at all and loses a full step per tick, which is worse than today. Keep `canRide` unchanged for the floor snap in `missingCarry`, `returningJump`, `animationSupport` and the camera recording, so the documented floor-lock release, Fresh animation and camera behaviour stay as they are.

**How to test.**

- *Unit.* Extract the contact / overlap / launch decisions into a pure state machine (inputs per tick: jumping, onGround, vertical speed, inherited velocity, contact registered, carrier step). Drive it with launch, 11 airborne ticks, a landing with jumping still true, then a re-jump. Assert that the re-jump seeds the current step (0.832), not `0.832 × 0.99^12 ≈ 0.7375`. Add a control with jumping false at landing (passes today), a case where contact on the rising launch tick must not relaunch, and a case where a failed launch validation keeps the old release.
- *In game.* Use the powered-elevator fixture from TESTING.md with the carrier moving and the cabin parked. Stand at the cabin centre, hold jump for 60 ticks. Log each tick: the rider's cabin-local X, `sable$getInheritedVelocity().x`, `onGround`, and `airborneCarry.motion` at each launch. Before the fix the inherited velocity at each re-jump reads about 0.74, 0.65, 0.58 and the local X falls back hop by hop. After the fix every launch reads about 0.83 and the loss per hop stays at the hop-1 value.

**Verification status.** Confirmed independently by a code-trace verifier and by a quantitative verifier (confidence 0.8 each); vanilla and Create links were checked in bytecode and the Minecraft sources. Not run in game. One link remains an assumption shared with the mod's own code: that Sable does not track the rider while they stand on the Create cabin floor (if Sable tracks them, line 141 drops the release and the native-launch path is used instead).

---

### P-03 (Medium) — Landing on a cabin other than the launch cabin applies the ship's motion twice

**Where.** `SableElevatorRiderCarry.java`: `removeOverlappingCarrierMotion` lines 78–98 (in particular the bare `return movement` at lines 85–86), `contact` lines 59–61, `beforeTravel` line 152.

**What is wrong.** `removeOverlappingCarrierMotion` removes Sable's already-applied horizontal velocity from Create's carry in three cases only: Sable carried the rider in travel this tick, the stored `AirborneCarry` belongs to *this same cabin*, or a `SableElevatorNativeLaunch` matches this cabin. An untracked rider who lands on a different cabin matches none of them, so line 85–86 returns Create's full contact motion while Sable's inherited velocity is still being applied in travel, and nothing ever consumes that velocity.

Two cases:

- **(a) Cabin A to cabin B on the same ship.** This one is caused by the mod's own state. The `AirborneCarry` is keyed to A; `contact()` only clears its `landingCarrier` (lines 59–61) and keeps it alive. While it lives, `beforeTravel` returns at line 152, which also blocks proper launches from B.
- **(b) Jump from the plain deck into a cabin**, staying airborne long enough for Sable to stop tracking. This is an upstream Sable + Create gap that the mod does not cover; the documentation only promises the same-cabin return. It is listed because the README states that boarding happens "without applying the ship's movement twice".

**Failure scenario.** Ship at about 0.83 blocks/tick. The rider jumps from cabin A, or from the deck at least a block away from the cabin, and lands in the middle of a cabin. From the landing tick on, the rider moves by Create's carry plus Sable's leftover velocity: about 1.6 blocks per tick against the cabin's 0.83. The rider slides forward at roughly ship speed relative to the cabin until the front wall stops them or they leave through an open side. The total excess is about 2.5 blocks if Sable's ground drag (0.7 × 0.99) applies, and wall-limited if only the 0.99 airborne drag applies.

**Proposed fix.** In `removeOverlappingCarrierMotion`:

1. Accept a release from any cabin on the same carrier (fixes case a through the existing, tested consume path):

   ```java
   boolean usingRelease = release != null && release.player.get() == player && release.tick == player.tickCount
           && (release.cabin.get() == cabin
               || release.cabin.get() != null && Sable.HELPER.getContaining(release.cabin.get()) == carrier);
   ```

2. Replace the bare `return movement` at lines 85–86 with a general untracked-landing fallback that runs after the native-launch branch (fixes case b):

   ```java
   if (tracking != null || !canRide(player) || !player.onGround() || player.getDeltaMovement().y > 0) return movement;
   // existing nativeLaunch branch first, unchanged
   Vec3 step = carrierStep(carrier, player.position());
   double size = step.horizontalDistanceSqr();
   if (!(size > 1e-8 && size <= MAX_CARRIER_STEP * MAX_CARRIER_STEP)) return movement;
   var inh = inheritedVelocity(player);
   double overlap = Mth.clamp(Math.min(inh.x * step.x + inh.z * step.z, movement.dot(step)) / size, 0, 1);
   if (overlap <= 0) return movement;
   Vec3 credit = step.scale(overlap);
   consumeHorizontalCredit(player, credit, 1);
   return movement.subtract(credit);
   ```

   The `min(...)` cap mirrors the native-launch path and prevents subtracting more than Create would move. With zero inherited velocity the overlap is 0, so ordinary standing, walking aboard, same-cabin returns and the missed-contact carry are unchanged.

   Known weakness to review: this estimates what Sable applied from the *current* inherited velocity (after that tick's drag), not from the displacement that actually resolved. If vanilla collision clipped the move, it over-subtracts slightly. A stricter variant snapshots the inherited velocity in `beforeTravel` and caps the credit by the resolved displacement, as `SableElevatorNativeLaunch.applied` does.

**How to test.**

- *Unit.* Extract the overlap arithmetic into a package-private pure method and assert: inherited (0.7755, 0) with step and movement (0.832, 0) gives about 0.932; zero inherited gives 0; inherited opposite to the step gives 0; inherited larger than the step clamps to 1; movement already reduced to (0.4, 0) gives about 0.48.
- *In game.* (a) Two elevator cabins about 3 blocks apart on one moving ship; jump from A into B. (b) Stand on the deck 2 blocks from a cabin and jump in. For 30–40 ticks after touchdown log `Sable.HELPER.getTrackingSubLevel(player)` (expect null at landing), `sable$getInheritedVelocity`, the contact motion before and after the hook, and the rider's cabin-local position. Before the fix the local position advances about 0.8 blocks per tick; after it, the first grounded tick consumes the velocity and the rider stays put. In (a) also check that `airborneCarry` no longer points at A afterwards.

**Verification status.** Confirmed by two independent verifiers (confidence 0.65 and 0.72). Unverified links: whether the rider is actually untracked at touchdown in common builds (it depends on whether ship blocks lie within Sable's detection range of the landing spot), and which drag factor applies on the cabin floor. Not run in game.

---

### P-04 (Medium, niche trigger) — Spectated player's helmet is no longer hidden by FirstPerson

**Where.** `src/main/java/dev/caleb/emfattachments/mixin/FirstPersonHeadSlotScopeMixin.java:24` and `client/BodyRenderScope.java:22-23`.

**What is wrong.** FirstPerson hides the helmet by returning an empty stack for the HEAD slot of any `Player` while its body pass is rendering. This mod narrows that to `BodyRenderScope.isLocalBody(player)`, which requires `entity == minecraft.player`. But FirstPerson's body pass renders `camera.getEntity()`, which is another player when you spectate them in first person (FirstPerson 2.7.3 `WorldRendererMixin.render` has no local-player or spectator check). For that player `isLocalBody` is false, so the helmet is no longer emptied, while FirstPerson still hides the head part.

**Failure scenario.** FirstPerson 2.7.3 installed. The local player switches to spectator mode, left-clicks another player who wears a helmet and stays in first-person view. The helmet model is drawn around the camera and obstructs the view. Without this mod FirstPerson hides it. The size of the obstruction is inferred from geometry, not observed.

**Proposed fix.** Add a `BodyRenderScope.isCameraBody(LivingEntity)` that runs the same checks as `isLocalBody` but drops the `entity != minecraft.player` clause (keep: same thread, entity is `minecraft.getCameraEntity()`, first-person camera type, FirstPerson enabled, camera not detached and `camera.getEntity() == entity`, not the Iris shadow pass, `isRenderingPlayerRaw()`, not `cameraEntityExtract`). Use it in `FirstPersonHeadSlotScopeMixin` line 24 in place of `isLocalBody`. Leave every other caller of `isLocalBody` unchanged.

**How to test.** Two clients (or a LAN world), both with FirstPerson 2.7.3. Player B wears an iron helmet; player A spectates B in first person and takes a screenshot. Before the fix the helmet covers part of A's view; after it the view is clear. Regression checks: A's own helmet is still hidden in first person and visible in F5; B's helmet is visible when A merely looks at B; the shadow pass and inventory preview keep the helmet.

**Verification status.** Confirmed by one adversarial verifier from FirstPerson bytecode (confidence 0.82); I read both of this mod's files and they match the description. Not run in game.

---

### P-05 (Low) — Pausing singleplayer during a deck-to-cabin jump drops the launch record

**Where.** `client/SableElevatorNativeLaunch.java:44-45` and `SableElevatorRiderCarry.java:201-203`.

**What is wrong.** `SableElevatorNativeLaunch.observe` requires `player.tickCount == tick + 1`. Create's contraption handler runs from `ClientTickEvent.Post` even while the game is paused (NeoForge fires that event unconditionally; Create checks only that a level and player exist), but the player does not tick. On the first paused tick `observe` sees `tickCount == tick`, returns false, and `recordCameraMovement` sets `nativeLaunch = null`. After unpausing, the landing takes Create's full contact motion on top of what Sable already applied.

**Failure scenario.** Singleplayer, not opened to LAN. Jump from the tracked deck toward a cabin on a moving ship and press Esc while airborne, then resume. On landing the rider gets up to one extra carrier step and keeps unconsumed inherited velocity.

**Proposed fix.** In `observe`, after the validity checks and before the `tick + 1` check, add `if (player.tickCount == tick) return true;` so a repeated collision pass without a player tick leaves the record unchanged. Optionally skip the RETURN-hook work in `SableElevatorCollisionMixin` when `Minecraft.getInstance().isPaused()`.

**How to test.** Unit: call `observe` twice with the same tick count and valid data; the second call must not invalidate the record. In game: read `nativeLaunch` by reflection before and after a mid-jump pause; without the fix it becomes null on the first paused tick.

**Verification status.** Confirmed by one verifier from the Minecraft sources and Create bytecode (confidence 0.8).

---

### P-06 (Low) — Not Enough Animations smoothing can replace the first-person bow and crossbow corrections

**Where.** `mixin/FirstPersonNeaBowPoseMixin.java`, `mixin/FirstPersonNeaCrossbowPoseMixin.java:30`, with the scope test in `client/BodyRenderScope.java`.

**What is wrong.** The two corrections run only for the local first-person body pass, which excludes the Iris shadow pass. NEA then smooths arm rotations with one history per player and records its target only on the first `setupAnim` of each client tick. If the shadow pass renders the local player first in a tick, the uncorrected pose becomes the target and the body pass replays it.

**Narrower than first reported.** When EMF is animating the player model (Fresh Animations with the player extension, which is the documented and tested setup), EMF Compat Core re-applies the corrected snapshot after NEA's smoothing, so nothing is visible. The defect only shows when the EMF bridge mods are installed but no EMF player animation is active, together with a shadow-casting shader pack. In that setup `animation.firstPersonBowPose` and `animation.firstPersonCrossbowPose` appear to do nothing.

**Proposed fix.** Give NEA's smoothing history the same per-pass separation this mod already gives Core's interpolation history (`FirstPersonPoseStates`): either skip NEA's target capture during the shadow pass for the local player, or re-apply the correction after NEA's `updateModel` in the body pass. Alternatively document the limitation.

**How to test.** FirstPerson, NEA (smoothing on, the default), EMF, EMF Compat Core, the NEA bridge, Iris with a shadow-casting shader pack, and **no** player animation pack. Draw a bow in first person: the NEA custom bow pose is visible although `firstPersonBowPose` is enabled. Disabling shadows or NEA smoothing makes the correction work.

**Verification status.** Partly confirmed by two independent verifiers (confidence 0.75 each); both agree on the mechanism and on the narrower trigger.

---

### P-07 (Low, latent) — `missingCarry` does not discount an active airborne release

**Where.** `SableElevatorRiderCarry.java` lines 260–264 and 305.

**What is wrong.** In the supported, floor-found branch the only discount applied to the carrier step is `nativeCarry`, taken from Sable's tracked `collision.inheritedMotion`. If an `AirborneCarry` is alive whose velocity Sable already applied in this tick's travel, that displacement is not subtracted, so the rider would move by roughly the release plus a full carrier step. `removeOverlappingCarrierMotion` handles the equivalent case for native contact; `missingCarry` checks `airborneCarry` only in the no-floor branch (lines 287–288).

**Reachability.** The originally reported trigger (a fast-rising floor) was refuted. One verifier found a possible trigger, a one-tick jump tap that hits the cabin ceiling or a wall on the press tick; the other could not establish any in-game sequence. Treat this as a code gap to close, not a demonstrated bug.

**Proposed fix.** In that branch, when `airborneCarry` belongs to this player and `airborneCarry.tick == player.tickCount`, subtract its overlap with `movement` the same way lines 261–264 do for `nativeCarry`.

**How to test.** Unit-level only for now: build the state by hand (support set, `carriedTick` one tick old, a live release for the current tick, floor found) and assert that the returned vector is the carrier step minus the release overlap.

**Verification status.** Code gap confirmed by two verifiers; trigger unproven.

---

### P-08 (Low) — Reduced-tick version gate admits pre-release builds

**Where.** `src/main/java/dev/caleb/emfattachments/compat/SableReducedTickLinker.java:27-31`.

**What is wrong.** `supported()` expresses "exactly the inspected families" as `atLeast(lower) && !atLeast(nextPatch)`. Maven ordering puts a pre-release before its release, so `2.0.7-beta.1`, `2.0.7-rc1` and `1.12.3-beta` all compare below the upper bound and pass. I ran the comparator: `2.0.7-beta.1` versus `2.0.7` gives −4, `1.12.3-beta` versus `1.12.3` gives −4. On the Sable side the linker fails safe, but `SablePlayerReducedTickMixin` has `require = 1` into Entity Culling's `basicTick`, so a changed pre-release could crash at startup, and an unchanged one runs reduced ticks on a pair the README says falls back to full ticks.

**Proposed fix.** Compare on the numeric release only, for example require `getMajorVersion()/getMinorVersion()/getIncrementalVersion()` to equal the inspected values and `getQualifier() == null`, or use an explicit allow-list of version strings as the elevator gates do.

**How to test.** Extend `SableReducedTickLinkerTest` with a predicate reporting Sable `2.0.7-beta.1` and Entity Culling `1.12.3-beta`; `supported` must return false.

**Verification status.** Confirmed by me by running `DefaultArtifactVersion` on those strings.

---

### P-09 (Low) — Left-handed player: a glove equipped after the ragdoll formed is not drawn

**Where.** `mixin/RagdollArtifactArmMixin.java:18`, `mixin/RagdollCataclysmArmMixin.java`, `client/RagdollAccessoryArmScope.java:34-37`, with the routing in `mixin/RagdollHeadwearRoutingMixin.java`.

**History of this finding.** It was first reported as "left-handed players lose their Artifacts gloves on a ragdoll in an Accessories-only setup". That is **refuted**: the arm mixins are only applied when Sable Player Ragdoll, Accessories, Curios and the Accessories Compat Layer are all installed (`OptionalMixinPlugin.java:130-134, 163-167`), and in that setup two independent traces show every glove is drawn exactly once on the correct arm for both handednesses. The Accessories draw that this mod cancels is replaced by the Curios-bridge draw of the same item in the other arm part.

**What remains.** That replacement depends on Sable's Curios snapshot, which is taken only when the ragdoll is assembled. While a ragdoll is live, Sable re-syncs Accessories only: `RagdollEquipmentHelper.syncAccessoriesAndSend` calls `RagdollAccessoriesEquipmentHelper.applyFrom` and `sendPartUpdates` and nothing for Curios (I confirmed this in the 0.8.1 bytecode). So a glove that reaches the Accessories data after assembly has no Curios copy. Sable routes hand slot 0 to the right-arm part and slot 1 to the left-arm part by index parity; the Artifacts and Cataclysm renderers pick the arm from the owner's main hand; for a left-handed online owner these disagree, and this mod cancels the draw. The glove is drawn nowhere.

**Failure scenario.** Sable Player Ragdoll 0.8.1, Accessories beta.54, Curios 9.5.1, Accessories Compat Layer 0.1.13, Artifacts 13.2.5 (or Cataclysm 3.33 claws/gloves). Main Hand set to Left, hand slots empty. Get ragdolled while online, then equip Feral Claws in hand slot 0 while the ragdoll is still active. The claw does not appear on the ragdoll. A right-handed owner sees it; a left-handed owner who wore it before ragdolling sees it. Without this mod the same glove is drawn floating beside the wrong arm, so the mod turns a misplaced draw into no draw.

**Proposed fix.** Make the Accessories split-arm routing follow handedness for the renderers whose arms this mod filters. In both `slotIndexBelongsToPart` wrappers of `RagdollHeadwearRoutingMixin`, when the slot is `hand` or `ring`, the part is an arm part, and the stack resolves to an Artifacts glove renderer or a Cataclysm claw/glove renderer, return `part == armPart(index % 2 == 0 ? entity.getMainArm() : entity.getMainArm().getOpposite())` instead of Sable's fixed parity. For right-handed owners and for the doll entity (always right-handed) this equals Sable's routing. Separately, `RagdollAccessoryDrawScope` records an Accessories draw as completed even when its only arm draw was cancelled; recording only after a non-cancelled draw would make the ledger match its contract (harmless today).

**How to test.** Unit: feed the routing helper a fake entity with main arm LEFT and RIGHT, slots `hand`/`ring`, indexes 0–3 and each part; right-handed results must equal Sable's parity and left-handed results must swap the arm parts for glove stacks only. In game: the scenario above, then repeat with the gloves equipped before ragdolling, both handednesses, offline and as a corpse; each glove must appear exactly once.

**Verification status.** One verifier established the narrow case (confidence 0.7); the other refuted the broad claim and did not examine mid-ragdoll equips. I confirmed the "Accessories-only live re-sync" link from bytecode. Unverified: whether a player can actually change accessories while ragdolled in game. Also note `testmod`'s `RagdollClawProbe` reflects an older `renderFromStored` signature and does not exercise 0.8.1's routing.

---

## 4. Hardening notes (not bugs with any version that exists today)

These were raised as findings and then downgraded after checking real versions.

| Note | Detail | Suggested action |
|---|---|---|
| Unbounded upper versions on MixinSquared and internal targets | `SableSleepingRotationMixin` / `SableSleepingNameTagMixin` (gate: Sable present, line 93–96), `SablePlayerEntityCullingMixin` (line 75–76), `FirstPersonPoseInterpolatorMixin` (`require = 8`, line 156–159) and the five `SableFresh*` mixins have no upper version bound. **Checked:** the sleeping handlers match on every Sable release from 1.0.4 to 2.0.6, the culling handler on Entity Culling 1.7.4 to 1.12.2, the interpolator on EMF Compat Core 1.0.0 to 2.4.0, and the Fresh targets on EMF 2.4.1 to 3.3.12 (the latest). A future release that renames one of these would crash at startup | Optional: pin upper bounds the way the elevator and reduced-tick gates do, or relax `require` with a logged fallback for the cosmetic ones |
| `InvisiblePlayerLayerPoseMixin` applies only on EMF exactly 3.3.12 | `neoforge.mods.toml` allows `[3.3.12,)`, so the invisible-player pose fix will silently disappear on the next EMF release | Decide whether to widen the gate after checking the next EMF version; add a log line when the gate is skipped |
| Core 2.4+ assumed to own Sable deck height even if Core's own correction failed | `compat/EmfSableCompatibility.java:11-33`. Core 2.4.0 turns its correction off silently when its reflection into Sable fails. Not reachable with Sable 2.0.6; it would need a future Sable that renames the reflected members, and this mod cannot observe Core's failure flag through a public API | None required now |
| Friends&Foes totem transform leaks into later curios | Upstream: Curios 9.5.1 pushes/pops once around all curios and Friends&Foes' totem renderer has no push/pop. `FriendsAndFoesTotemMixin` changes only the crouch part of a transform that already escapes; it adds no new leak | Optional containment: wrap the totem render in `pushPose()/popPose()` with `@WrapMethod` |

---

## 5. Test harness, dev scripts and documentation

All are low severity except T-06, which can hide a real rendering regression from the release record. They matter because several can produce a "verified" record that the evidence does not support. Every row was confirmed either by me reading the code or by an independent verifier.

### 5.1 Test harness (`src/testmod`)

| ID | Location | Defect | Fix |
|---|---|---|---|
| T-01 | `test/SableElevatorCollisionProbe.java:225` (and `:239`) | `reviewContacts` returns early unless the mod version string is exactly `"1.2.19"` or `"1.2.20-dev"`; the footprint check needs exactly `"1.2.20-dev"`. After the next version bump the floor-witness and footprint checks vanish and the report still shows only passing rows | Remove the version test (detect the methods by reflection and record `"skipped": true` with a reason when absent), and make the review fail when expected rows are missing |
| T-02 | `test/mixin/SableElevatorRiderCarryProbeMixin.java:46-47` | `@WrapOperation(method = "hasFloor", ... BlockGetter.clip)` no longer matches: production `hasFloor` (`SableElevatorRiderCarry.java:392-394`) only calls `floorHeight`, and the `clip` call is at line 411 inside `floorHeight`. The test config is required with `defaultRequire = 1`, so enabling `-Demfattachments.sableElevatorCarryProbe=true` fails injection | Retarget to `method = "floorHeight"` |
| T-03 | `test/SableElevatorLiveSequence.java:586` | Camera evidence stops recording at 3,600 rows with no flag. The per-leg camera verdict (lines 1100–1101) is then computed from a prefix and can pass on a partial descent | Record `cameraRowsTruncated` and fail the camera verdict when it is set, or cap per phase instead of globally |
| T-04 | `test/LiveCombatSequence.java:106` | Final status is `violations.isEmpty() ? "CAPTURED" : "FAIL"` with no check that any owned combat frame was observed. If ownership is never claimed the run ends CAPTURED with zero violations. The sibling `OverlapCombatSequence` has the guard (`hookFrames > 0`) | Require `ownedFrames > 0` overall and per scenario |
| T-05 | `test/AttachmentTestMod.java:393-409` | In the renderer sweep, a screenshot or report failure sets `complete = true`; closing the screen then writes `"finished": true` over truncated results, and `scripts/summarize_tests.py` copies that flag through | Track `aborted` separately and write `finished = finished && !aborted` |
| T-06 | `test/ExpandedSequentialAbilitySequence.java:1353` (also 275, 1278–1280, 1358–1371), read by `scripts/reconcile_final_native_outcomes.py:86` | `complete()` writes the row's status and `rendererViolations` once, then most cases continue into an EXPIRE or STORM_DRAIN phase in which `frame()` keeps counting violations (up to about 1,800 ticks for Tempest). Nothing writes the later count back and `next()` resets it, so the row keeps PASS with 0 violations and the release reconciler reads that 0. A regression that only shows while the weapon is held after its ability fires is not recorded | In `next()`, before the reset, compare the count with its value at `complete()`; if it grew, update `rendererViolations` and set the row to RENDER_FAILURE |
| T-07 | `test/ExpandedEquipmentSequence.java:325-338` | Same pattern as T-05 in a second sweep screen: a screenshot or report failure sets `complete = true` and closing the screen writes `finished: true` | Same fix as T-05 |
| T-08 | `test/NeaProjectileLifecycleSequence.java:156` | `bodyExpected = !firstPerson || CombatRenderCounters.bodyPasses > 0`, and that counter only increments when production's `isLocalBody` is true, which is the same predicate the bow fix uses. If `isLocalBody` stops matching, the fix turns off and the first-person bow assertions are skipped together | Derive the expectation from harness state (first person and FirstPerson enabled) and require exactly one body pass |
| T-09 | `test/OverlapCombatSequence.java:193-215` | `hookFrames` is session-wide and never reset per case. A case that never enters owned combat rendering adds no failures, and the run still ends CAPTURED if any other case had one hook frame | Count owned and hook frames per case and fail a case that has none |
| T-10 | `test/LiveFirstPersonSequence.java:103-105, 206`, `scripts/summarize_tests.py:128-138` | The embedded `FirstPersonProbe` result is stored but never read. The sequence ends CAPTURED even when the probe status is FAIL, and the summary drops the probe status | Downgrade to FAIL when the probe did not pass; carry the probe status into the summary |
| T-11 | `test/GlErrorTraceProbe.java:136-183` | Reports CAPTURED / NO_MATCHING_ERROR_OBSERVED even when GL debug output was never enabled (`glDebugVerbosity` 0 or no debug extension), in which case it cannot observe any error | Record whether the debug callback is installed and report NOT_OBSERVABLE otherwise |
| T-12 | `test/RevivalFaceSequence.java:177-178, 251` | The fixture clears a 17×17×7 volume around the player's current position and restores block states only, so containers drop their contents and block entities lose their data. It is guarded by the disposable-client check, so it only matters when the trigger is run by hand in a world worth keeping | Build the arena at a fixed remote coordinate and refuse to overwrite positions that hold block entities |

### 5.2 Dev scripts (`scripts/`)

| ID | Location | Defect | Fix |
|---|---|---|---|
| S-01 | `record_installation.py:10` | Hard-coded `version = '1.2.3'`; every path is built from it, so the script cannot record the current 1.2.20-dev install (it raises `FileNotFoundError`) | Read the version from `build.gradle` or take it as an argument |
| S-02 | `install.ps1:17-31` | The FirstPerson step runs after old jars were moved (line 17) and the new jar copied (line 20). If the `.disabled` FirstPerson 2.7.3 file is missing, line 26 throws and `changes.json` (line 31) is never written. The step also checks only the exact 2.7.3 file name, so with another FirstPerson version active it enables a second FirstPerson jar | Validate the FirstPerson precondition before touching the mods folder; write `changes.json` in a `finally`; check for any active `firstperson-*.jar` |
| S-03 | `audit_sable_release.py:22-99, 108` | Every release-integrity gate is a bare `assert`, then `status='PASS'` is written unconditionally; under `python -O` or `PYTHONOPTIMIZE` all gates are skipped. The script is also stale: line 31 asserts exactly 80 unit tests (there are 210) | Replace asserts with explicit checks that raise; un-pin the test count |
| S-04 | `check_optional_runtime.py:263-293`, `check_aether_cape_runtime.py:203-269` | Same pattern: verdicts gated only by `assert`, then `collection.json` is written with hard-coded clean values and PASS | Same fix; derive the recorded fields from the checked values |
| S-05 | `audit_sable_tick_scope.py:18` | `HELPER = "dev/tr7zw/entityculling/NMSCullingHelper"`, but Entity Culling 1.12.x ships `dev/tr7zw/entityculling/util/NMSCullingHelper` (checked in the 1.12.0 and 1.12.2 jars). Line 206 raises `KeyError` on supported versions | Accept both paths, as the production mixin does |
| S-06 | `run_test_queue.py:63-68` | `RESTORE_FAILURE` is not in the terminal status set. Five sequences write that status without `finished: true`, so the runner prints RUNNING until the overall timeout (default 10,800 s) and then raises `TimeoutError` instead of reporting the failure | Add `RESTORE_FAILURE` to the terminal and failed sets |
| S-07 | `test_runtime.py:60-73` | `prepare` only copies missing files into the disposable client and never removes third-party jars. A mod the user disabled or removed stays active in the test client, and the run manifest (which hashes only this mod's jars) does not show the drift | Mirror the instance `mods` folder (retire extras), and record all mod hashes |
| S-08 | `record_build.py:14-36` | Binds whatever JUnit XML is in `build/test-results` to the freshly hashed jar and sources. After `gradlew jar` (tests not run) it records old passing results next to new code | Require the XML to be newer than the compiled classes |
| S-09 | `run_artifice_tests.py:108-116` | `launch` re-copies the production and harness jars from `build/libs` without comparing them to the hashes recorded by `prepare`, so `inputs.json` can name a different artifact from the one that ran | Compare hashes and refuse on mismatch |
| S-10 | `launch_crowd_pass.ps1:3, 17-30` | The world is reset relative to `$PSScriptRoot`, but the embedded Python resolves the runtime from the current directory. Started from elsewhere, the world is archived and reset, then the launch fails, and a retry with the same attempt number is refused | Pass the repo path to Python explicitly; dry-run the import before moving the world |
| S-11 | `audit_reported_fixes.py:38-132`, `reconcile_final_native_outcomes.py:76-234`, `export_without_fpm_final_review.py:24-97`, `export_focused_relic_ionbound_review.py` | Same assert-only gating as S-03 and S-04, followed by a constant PASS / RECONCILED / REVIEWED status | Same fix |
| S-12 | `summarize_expanded_tests.py:37-51` | Live-wearable coverage counts a case as covered if any matching run ever passed it. A later FAIL for the same case leaves `allEnumeratedNativeUseCasesObserved` true, and the flag is also true for an empty catalog | Accept only runs with an accepted terminal status and clean restore flags, let later runs override earlier ones, and require a non-empty catalog |

### 5.3 Documentation

| ID | Location | Issue |
|---|---|---|
| D-01 | `README.md:87` | Names the production artifact as `emf-attachments-compat-1.21.1-1.2.15.jar`; `build.gradle:7` and `neoforge.mods.toml` produce `1.2.20-dev`, and the README's newest entry describes 1.2.19 |
| D-02 | `COMPATIBILITY.md` (row "Fresh Player repeatedly bends while standing or walking on a moving Sable deck") | Says "Skip all four hooks with Core 2.4.0+". `README.md:21`, the code (`OptionalMixinPlugin.java:97-104`) and the tests keep the Fresh hooks with Core 2.4+ as a rounding guard |

---

## 6. Checked and found sound, and refuted claims

**Refuted.** "Aether gloves are drawn on an invisible local player during PlayerAnimator first-person attacks" (`client/AetherNativeAttackGloves.java`). The adversarial verifier showed this matches what the native Accessories layer and held-item layer do for invisible players (vanilla shows equipment on invisible players in a third-person model pass). Not a defect.

**Found sound** (each item was checked against bytecode of the documented versions unless stated):

- All 73 mixins: target classes, method descriptors, `@At` targets and their occurrence counts, `@Local` uniqueness, `@Shadow`/accessor targets, MixinSquared handler names. `emfattachments.mixins.json` lists exactly the 73 classes (71 client, 2 common).
- No mixin can apply while a mod it hard-references is absent. Exact-version pins (`sable 2.0.6`, `create 6.0.10`, `betterthirdperson 1.9.0`, `entity_model_features 3.3.12`) match the literal versions in those jars' metadata.
- Elevator code: frames of reference, the footprint clipping, landing correction, release-motion sampling, tick ordering (player tick, then Create's collision in `ClientTickEvent.Post`, then the deferred position packet), thread safety on the integrated server, weak references across respawn and dimension change, no stuck `resolvingFloorContact` / `floorCredit` / safety-lock state.
- Reduced ticks: the ASM linker matches Sable 2.0.6, every failure path leaves full ticks, and the order of Sable's callbacks before `aiStep` matches a full tick on Entity Culling 1.12.0 to 1.12.2.
- First-person arm masks, handedness and slot parity; crossbow and gun pose maths against vanilla, NEA and Iron's code; Better Combat fade and speed-modifier weights against PlayerAnimator.
- Every scope, mask and ThreadLocal is restored in `finally` or try-with-resources, including when another mod cancels or throws.
- Config: all 18 options are read, none inverted, defaults inside their ranges, all translation keys present.
- Mixin interplay: no hard conflict with any mixin of the 45 reference jars on the methods this mod injects into.
- Fuzzing against the compiled classes: `SableElevatorFootprint.topHeight` (230,000 cases including overturned and far-coordinate boxes), `landingCorrection` (200,000 descents plus NaN/infinity inputs), a model of `SableElevatorReleaseMotion.sample` (200,000 cases), and the version gates. No violation.

---

## 7. Coverage and limits

- **No in-game execution.** Every scenario above still needs a live reproduction. The elevator findings in particular rest on how Sable tracks a rider standing on a Create contraption, which was not observed at runtime.
- **Reference versions.** Mixin targets were validated against the versions the repository documents (Sable 2.0.6, Create 6.0.10, Entity Culling 1.12.0–1.12.2, FirstPerson 2.7.3, EMF 3.3.12, ETF 7.2.6, EMF Compat Core 2.4.0, Accessories 1.1.0-beta.54, Curios 9.5.1, and so on), plus the versions listed in P-01. Where the repository documents no version, the newest 1.21.1 NeoForge release was used.
- **Not available.** "RagRevival 1.4.0" (mentioned in TESTING.md) was not found on Modrinth. Supplementaries (targeted by one test mixin) had no reference jar. Fresh Animations' own resource-pack expressions were not available. Sodium/Embeddium fast paths were not checked.
- **Test harness and scripts.** `src/testmod`, `src/perfmod` and `scripts/` were reviewed only for false passes, behaviour-changing probes, and damage to the developer's instance. Most files were read in full by one reviewer; a few large sequences were partly skimmed. Historical evidence-binder scripts pinned to old versions were treated as intentional.
- **Build.** The build depends on a local mods folder (`-PinstanceMods`). It was reproduced in a scratch copy with the reference jars; the test-harness and perf source sets were not compiled.

---

## 8. Reproducing the static checks

Version lists and downloads come from Modrinth:

```
https://api.modrinth.com/v2/project/<projectId>/version?loaders=["neoforge"]&game_versions=["1.21.1"]
```

Member presence, per jar:

```
unzip -l <mod.jar> | grep <ClassName>.class
javap -p -cp <mod.jar> <fully.qualified.ClassName> | grep <member>
javap -c -p -cp <mod.jar> <fully.qualified.ClassName>      # bytecode with offsets
```

Nested jars are under `META-INF/jarjar/` inside the mod jar (for example Sable's `sable-companion-common` and the Accessories copy inside Aether).

Version ordering, as the loader computes it:

```java
new org.apache.maven.artifact.versioning.DefaultArtifactVersion("2.0.7-beta.1")
        .compareTo(new DefaultArtifactVersion("2.0.7"))   // negative
```
