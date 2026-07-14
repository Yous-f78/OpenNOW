# Mobile Touch Controls for OpenNOW

This branch adds a mobile-style touch control overlay to the OpenNOW
Android client, designed for playing GeForce NOW games with touch
controls the way mobile games (PUBG Mobile, COD Mobile, Fortnite Mobile)
handle it — instead of the default full Xbox controller overlay.

## What This Adds

### Mobile Layout Mode
When enabled, replaces the full Xbox controller overlay with a
streamlined mobile layout:
- **Left side**: floating joystick for movement (appears at touch point)
- **Right side**: A/B/X/Y face buttons + RT/LT triggers
- **Top corners**: LB/RB bumpers (index finger reach)
- **Rest of screen**: camera look zone (drag to look, tap to click)

### Floating Joystick
Instead of a fixed-position stick, the joystick appears wherever your
left thumb first touches the left ~45% of the screen. This is the
standard in mobile games because it adapts to natural thumb position.
Can be toggled off for a fixed stick.

### Camera Look Zone
Any touch on empty screen space (not on a button or stick) is captured
as a drag gesture and sent as relative mouse movement to the stream —
this is what controls the camera/look in FPS and third-person games.
- Quick tap = left mouse click
- Sensitivity is adjustable (0.1x to 3.0x)
- Y-axis inversion toggle

### Per-Game Profiles
Each game gets its own touch settings profile. When you launch a game:
1. The profile for that game is loaded (if it exists)
2. Your current settings are saved to the previous game's profile
3. On stream exit, the current game's profile is saved

Profiles are stored as JSON in `files/touch_profiles/<gameId>.json`
and can be exported/imported for sharing.

### Settings
All settings are accessible in:
- **Settings → Input → Touch controls** section (global defaults)
- **Stream screen → Controls panel → Touch Layout** (quick toggle
  during gameplay)

## Files Changed

| File | Description |
|------|-------------|
| `MobileTouchControls.kt` (new) | CameraLookZone, FloatingVirtualStick, MobileActionButton, MobileTriggerButton, MobileLandscapeTouchControls, MobileFaceButtonCluster |
| `TouchProfileManager.kt` (new) | Per-game profile save/load (JSON in app storage) |
| `Models.kt` | Added fields to AndroidTouchSettings: mobileLayout, floatingJoystick, cameraSensitivity, cameraInvertY, cameraZoneHeight, activeProfileGameId |
| `OpenNowScreens.kt` | TouchOverlay routes to MobileLandscapeTouchControls when mobile layout enabled; StreamScreen loads/saves per-game profiles; StreamControlsPanel has mobile layout toggle; TouchControlGroup and VirtualStick made internal for reuse |
| `OpenNowSettingsScreens.kt` | Settings UI: mobile layout toggle, floating joystick toggle, camera sensitivity slider, invert Y toggle |

## How to Enable

1. Launch a game on GeForce NOW via OpenNOW
2. Open the controls panel (tap the controls button)
3. In the **Touch Layout** section, toggle **Mobile layout** to "On"
4. The overlay switches to the mobile-style layout
5. Use Settings → Input for camera sensitivity and other fine-tuning

## Design Decisions (Research-Based)

Based on research into PUBG Mobile 4-finger HUD layouts, COD Mobile
touch controls, and Fortnite Mobile:

1. **Floating stick over fixed stick** — adapts to thumb position,
   reduces mis-presses when the player's grip shifts
2. **Camera zone on empty screen** — maximizes look area without
   obstructing game visuals; matches how all major mobile shooters work
3. **Triggers above face buttons** — RT for shooting is placed above
   the A/B/X/Y cluster so the right thumb can reach both; LT for aiming
   is placed left-of-center
4. **Bumpers at top corners** — for index-finger players (4-finger claw
   grip), LB/RB are at the top corners for easy reach
5. **Tap-to-click on camera zone** — quick taps register as mouse clicks
   for interacting with menus without needing a separate button
6. **Per-game profiles** — different games need different layouts
   (e.g., an FPS needs a camera zone, a strategy game might not)

## Commit History

1. `a33182f` — Scaffolding: MobileTouchControls.kt + AndroidTouchSettings fields
2. `040193f` — Mobile landscape layout + floating joystick + camera zone
3. `d916e51` — Settings UI for mobile layout options
4. `75b83d0` — Per-game touch profile system
5. `eee8dde` — Mobile layout toggle in stream controls panel
