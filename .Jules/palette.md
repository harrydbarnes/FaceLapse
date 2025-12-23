# Palette's Journal

## 2024-05-22 - Empty State Pattern
**Learning:** Users responded well to rich empty states that include a large icon, title, and descriptive subtitle, rather than just "No items found".
**Action:** When implementing empty states, use a centered Column with `Icon(modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))`, a bold title, and a body text subtitle.

## 2024-05-23 - Swipe to Dismiss Backgrounds
**Learning:** Standard swipe-to-dismiss implementations often look "boxy" when revealing the background color.
**Action:** Apply `Modifier.clip()` matching the item's shape (e.g., `CardDefaults.shape`) to the background container of the SwipeToDismissBox to ensure corners are rounded during the animation.

## 2024-05-24 - Action Buttons on Images
**Learning:** Icon-only buttons on top of user-generated images (photos) often suffer from poor contrast.
**Action:** Always wrap action icons in a semi-transparent container (scrim) with a contrasting icon color (e.g., White on Black 50%). Ensure touch targets are at least 36-40dp even if the visual design is smaller, or use `IconButton` with a custom background.
