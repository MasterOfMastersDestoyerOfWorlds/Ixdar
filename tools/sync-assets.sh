#!/bin/bash
# sync-assets.sh - Sync S3 assets to local repository
# Usage: ./sync-assets.sh [--bucket <bucket>] [--prefix <prefix>] [--local <local_root>] [--profile <profile>]

set -e

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --bucket)
            S3_BUCKET="$2"
            shift 2
            ;;
        --prefix)
            S3_PREFIX="$2"
            shift 2
            ;;
        --local)
            LOCAL_ROOT="$2"
            shift 2
            ;;
        --profile)
            AWS_PROFILE="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Default values
S3_BUCKET="${S3_BUCKET:-ixdar-assets}"
S3_PREFIX="${S3_PREFIX:-models/shared/versioned}"
AWS_PROFILE="${AWS_PROFILE:-default}"

# Resolve local root from environment variable if not provided
if [ -z "$LOCAL_ROOT" ]; then
    LOCAL_ROOT="${IXDAR_ASSET_REPO_ROOT:-}"
fi

if [ -z "$LOCAL_ROOT" ]; then
    echo "ERROR: IXDAR_ASSET_REPO_ROOT environment variable not set."
    echo "Please set the environment variable before running this script."
    echo "Example: export IXDAR_ASSET_REPO_ROOT='/Users/username/IxdarAssets'"
    exit 1
fi

# Create local directory if it doesn't exist
if [ ! -d "$LOCAL_ROOT" ]; then
    echo "Creating local asset repository: $LOCAL_ROOT"
    mkdir -p "$LOCAL_ROOT"
fi

# Sync using AWS CLI
echo "Syncing assets from s3://${S3_BUCKET}/${S3_PREFIX} to ${LOCAL_ROOT}..."
echo "Using AWS profile: ${AWS_PROFILE}"

aws s3 sync "s3://${S3_BUCKET}/${S3_PREFIX}" "${LOCAL_ROOT}" --profile "${AWS_PROFILE}"

echo "Sync complete."
