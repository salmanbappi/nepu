#!/bin/bash
BUILD_GRADLE="src/all/nepu/build.gradle"

if [ ! -f "$BUILD_GRADLE" ]; then
    echo "Error: $BUILD_GRADLE not found!"
    exit 1
fi

# Extract current extVersionCode
CURRENT_CODE=$(grep -E "extVersionCode\s*=\s*[0-9]+" "$BUILD_GRADLE" | grep -oE "[0-9]+")

if [ -z "$CURRENT_CODE" ]; then
    echo "Error: Could not find extVersionCode in $BUILD_GRADLE"
    exit 1
fi

# Increment code
NEW_CODE=$((CURRENT_CODE + 1))
NEW_VERSION="14.${NEW_CODE}"

# Update build.gradle
sed -i -E "s/(extVersionCode\s*=\s*)$CURRENT_CODE/\1$NEW_CODE/" "$BUILD_GRADLE"

# Verify update
UPDATED_CODE=$(grep -E "extVersionCode\s*=\s*[0-9]+" "$BUILD_GRADLE" | grep -oE "[0-9]+")
if [ "$UPDATED_CODE" != "$NEW_CODE" ]; then
    echo "Error: Failed to update extVersionCode in $BUILD_GRADLE"
    exit 1
fi

# Export variables for GitHub Actions
if [ -n "$GITHUB_ENV" ]; then
    echo "NEW_CODE=$NEW_CODE" >> "$GITHUB_ENV"
    echo "NEW_VERSION=$NEW_VERSION" >> "$GITHUB_ENV"
fi

echo "Bumped extVersionCode from $CURRENT_CODE to $NEW_CODE (versionName: $NEW_VERSION)"
