#!/bin/bash
BUILD_GRADLE="src/all/nepu/build.gradle"

if [ ! -f "$BUILD_GRADLE" ]; then
    echo "Error: $BUILD_GRADLE not found!"
    exit 1
fi

# Extract current extVersionCode
CURRENT_CODE=$(grep "extVersionCode =" "$BUILD_GRADLE" | awk '{print $3}' | tr -d "}")
if [ -z "$CURRENT_CODE" ]; then
    echo "Error: Could not find extVersionCode in $BUILD_GRADLE"
    exit 1
fi

# Increment code
NEW_CODE=$((CURRENT_CODE + 1))
NEW_VERSION="14.${NEW_CODE}"

# Update build.gradle
sed -i "s/extVersionCode = $CURRENT_CODE/extVersionCode = $NEW_CODE/" "$BUILD_GRADLE"

echo "NEW_CODE=$NEW_CODE" >> $GITHUB_ENV
echo "NEW_VERSION=$NEW_VERSION" >> $GITHUB_ENV

echo "Bumped extVersionCode to $NEW_CODE (versionName will be $NEW_VERSION)"
