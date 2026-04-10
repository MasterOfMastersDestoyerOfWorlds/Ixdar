# sync-assets.ps1
# Sync S3 assets to local repository
# Usage: .\sync-assets.ps1 [-Bucket <bucket>] [-Prefix <prefix>] [-Local <local_root>] [-Profile <profile>]

param (
    [string]$S3_BUCKET = "ixdar-assets",
    [string]$S3_PREFIX = "models/shared/versioned",
    [string]$LOCAL_ROOT = $null,
    [string]$AWS_PROFILE = "default"
)

# Resolve local root from environment variable if not provided
if (-not $LOCAL_ROOT) {
    $LOCAL_ROOT = $env:IXDAR_ASSET_REPO_ROOT
}

if (-not $LOCAL_ROOT) {
    Write-Error "IXDAR_ASSET_REPO_ROOT environment variable not set."
    Write-Error "Please set the environment variable before running this script."
    Write-Error "Example: `$env:IXDAR_ASSET_REPO_ROOT = 'C:\Code\IxdarAssets'"
    exit 1
}

# Create local directory if it doesn't exist
if (-not (Test-Path $LOCAL_ROOT)) {
    Write-Host "Creating local asset repository: $LOCAL_ROOT"
    New-Item -ItemType Directory -Path $LOCAL_ROOT | Out-Null
}

# Sync using AWS CLI
Write-Host "Syncing assets from s3://$S3_BUCKET/$S3_PREFIX to $LOCAL_ROOT..."
Write-Host "Using AWS profile: $AWS_PROFILE"

try {
    aws s3 sync "s3://$S3_BUCKET/$S3_PREFIX" "$LOCAL_ROOT" --profile $AWS_PROFILE
    Write-Host "Sync complete."
} catch {
    Write-Error "Failed to sync assets: $_"
    exit 1
}
