# TODO - Whisprr Chat Fixes

## Completed
- [x] Fix toolbar icons visibility (history, theme toggle) - ensure white icons on dark toolbar
- [x] Fix message bubbles - sent=blue, received=white text, all text white for readability
- [x] Fix login button bug (color/tint issue)
- [x] Fix mute badge real-time update (refresh adapter on mute state change)
- [x] Build and test APK
- [x] Fix message decryption failure - auto-regenerate missing RSA keys instead of requiring re-registration
- [x] Update Firebase dependencies to latest versions
- [x] Update Google Services plugin to latest version
- [x] Fix kick/mute functionality
  - Change mute button to mic with slash (muted) / normal mic (unmuted)
  - Fix kick button - immediately remove from participant list
  - Fix mute all - change all mic badges, prevent muted users from sending messages
  - Fix kick all - remove everyone from room, show kick popup
  - Mute/kick shouldn't work on host
  - Muted users get popup when trying to send message
- [x] Implement kicked member rejoin restrictions
  - Kicked members can't rejoin directly - get popup "can't join as kicked out"
  - Rejoin request sent to host automatically with "Request to Rejoin" button
  - Host gets popup notification for rejoin requests showing: "<username> is requesting to join again after being kicked out"
  - Host can reject requests with permanent ban option
  - Permanently banned users get popup "permanently banned from <session> by the host"
  - Rejection uses consistent popup design matching app theme
- [x] Fix history badge border and symbol visibility - Removed stroke, increased minWidth to 80dp and padding to 20dp for full symbol
- [x] Fix room settings popup edges - Moved padding to LinearLayout, removed stroke from bg_dialog initially, then added blue border
- [x] Fix image/video message bubbles - Hide file info layout, show only preview, removed CardView margins and added cardUseCompatPadding="false"
- [x] Make all popups have curved edges and blue thin line border - Updated bg_dialog with 24dp radius and 1dp blue (#2196F3) stroke
- [x] Build final APK with all features - app-debug.apk (9.2 MB) created successfully
- [x] Fix popup behavior
  - Popups close on outside press or back swipe (except loading popup)
  - Make popups non-transparent
  - Make popup corners curved and consistent UI
  - Created custom rejection and permanent ban dialogs matching join/create room design
- [x] Fix kicked user rejoin issues
  - Show rejoin requests to host
  - Prevent kicked users from joining via "join chat" button

## UI/UX Improvements
- [x] Remove "welcome back" text from home page (wastes space, slices join button text) - Text not found, may have been removed already
- [x] Fix history button icon
  - Change to proper Google history symbol (ic_history_google) - ✓ Implemented
  - Keep blue color - ✓ #2196F3 tint applied
  - Make it less faded - ✓ Full opacity
- [x] Fix light/dark mode icons
  - Moon should be grey - Already implemented
  - Sun should be yellow - Already implemented
- [x] Fix mute badge appearance
  - Keep mic dark - Updated in ParticipantAdapter
  - Remove grey background - Updated in ParticipantAdapter
- [x] Center message preview like WhatsApp - Already implemented with proper margins
- [x] Fix allowed file types checkboxes in host settings - Already working correctly
- [x] Rename host settings to room settings - Already renamed in menu

## Permissions & Technical
- [x] Auto-take required permissions for file sharing (remove permission request popup on theme toggle) - ✓ Toast popup removed, permissions handled silently
- [x] Fix kick timing issues
  - User should get kicked out popup after being removed from room - Already implemented
  - Fix request popup not showing when kicked user tries to rejoin - Already implemented
