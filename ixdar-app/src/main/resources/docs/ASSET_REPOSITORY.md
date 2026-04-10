# External Asset Repository Documentation

This document describes the S3 + local cache workflow for large binary 3D asset files (e.g., OBJ models) that should not live in git.

## 1. S3 Bucket and Prefix Convention

### S3 Layout

```
s3://ixdar-assets/
├── models/
│   ├── shared/           # Shared/common models (e.g., Hand.obj, skull.obj)
│   │   ├── versioned/    # Versioned assets with semantic versions
│   │   │   ├── hand/
│   │   │   │   ├── v1.0.0/
│   │   │   │   │   └── Hand.obj
│   │   │   │   ├── v1.1.0/
│   │   │   │   │   └── Hand.obj
│   │   │   │   └── latest -> v1.1.0/
│   │   │   └── skull/
│   │   │       └── v2.0.0/
│   │   │           └── skull.obj
│   │   └── Hand.obj      # Unversioned alias (symlink or redirect)
│   └── projects/         # Project-specific assets
│       └── <project-name>/
│           └── versioned/
│               └── <version>/
│                   └── <model-name>.obj
└── textures/             # Optional: external textures
    └── shared/
        └── versioned/
```

### Naming Conventions

- **Bucket name**: `ixdar-assets` (or environment-specific: `ixdar-assets-dev`, `ixdar-assets-stage`)
- **Prefix structure**: `models/<category>/<versioned>/<asset-name>/<version>/<filename>`
- **Version format**: Semantic versioning (e.g., `v1.0.0`, `v2.3.1`)
- **Latest alias**: Use S3 redirect or symbolic link to point to latest version

### Environment-Specific Buckets (Optional)

For multi-environment workflows:

```
s3://ixdar-assets-dev/    # Development assets
s3://ixdar-assets-stage/  # Staging/QA assets
s3://ixdar-assets-prod/   # Production assets
```

## 2. Sync Workflow: S3 to Local Asset Repo

### Environment Variable

The local asset repository root is configured via:

- **Environment variable**: `IXDAR_ASSET_REPO_ROOT`
- **JVM property**: `-Dixdar.asset.repo.root=<path>`

### Sync Script (PowerShell)

Create `tools/sync-assets.ps1`:

```powershell
# sync-assets.ps1
# Sync S3 assets to local repository

param (
    [string]$S3_BUCKET = "ixdar-assets",
    [string]$S3_PREFIX = "models/shared/versioned",
    [string]$LOCAL_ROOT = $env:IXDAR_ASSET_REPO_ROOT,
    [string]$AWS_PROFILE = "default"
)

# Resolve local root from environment variable
if (-not $LOCAL_ROOT) {
    $LOCAL_ROOT = $env:IXDAR_ASSET_REPO_ROOT
}

if (-not $LOCAL_ROOT) {
    Write-Error "IXDAR_ASSET_REPO_ROOT environment variable not set."
    exit 1
}

# Create local directory
if (-not (Test-Path $LOCAL_ROOT)) {
    New-Item -ItemType Directory -Path $LOCAL_ROOT | Out-Null
}

# Sync using AWS CLI
Write-Host "Syncing assets from s3://$S3_BUCKET/$S3_PREFIX to $LOCAL_ROOT..."

aws s3 sync "s3://$S3_BUCKET/$S3_PREFIX" "$LOCAL_ROOT" --profile $AWS_PROFILE

Write-Host "Sync complete."
```

### Sync Script (Bash/CLI)

Create `tools/sync-assets.sh`:

```bash
#!/bin/bash
# sync-assets.sh - Sync S3 assets to local repository

S3_BUCKET="${IXDAR_S3_BUCKET:-ixdar-assets}"
S3_PREFIX="${IXDAR_S3_PREFIX:-models/shared/versioned}"
LOCAL_ROOT="${IXDAR_ASSET_REPO_ROOT:-}"
AWS_PROFILE="${AWS_PROFILE:-default}"

# Resolve local root from environment variable
if [ -z "$LOCAL_ROOT" ]; then
    LOCAL_ROOT="$IXDAR_ASSET_REPO_ROOT"
fi

if [ -z "$LOCAL_ROOT" ]; then
    echo "ERROR: IXDAR_ASSET_REPO_ROOT environment variable not set."
    exit 1
fi

# Create local directory
mkdir -p "$LOCAL_ROOT"

# Sync using AWS CLI
echo "Syncing assets from s3://${S3_BUCKET}/${S3_PREFIX} to ${LOCAL_ROOT}..."

aws s3 sync "s3://${S3_BUCKET}/${S3_PREFIX}" "${LOCAL_ROOT}" --profile "${AWS_PROFILE}"

echo "Sync complete."
```

Make executable: `chmod +x tools/sync-assets.sh`

### Manual Sync Commands

```bash
# Full sync of shared models
aws s3 sync s3://ixdar-assets/models/shared/versioned/ $IXDAR_ASSET_REPO_ROOT

# Sync specific model version
aws s3 sync s3://ixdar-assets/models/shared/versioned/hand/v1.1.0/ $IXDAR_ASSET_REPO_ROOT/hand/v1.1.0/

# Sync only if newer (uses ETag/modified time comparison)
aws s3 sync --only-newer s3://ixdar-assets/models/shared/versioned/ $IXDAR_ASSET_REPO_ROOT
```

## 3. Using Assets in Ixdar

### Loading Models from Asset Repo

Models are loaded via the `ModelLoadScene` or directly via `FileManagement`:

```java
// In a Scene or Platform code
String modelFileName = "Hand.obj";
ModelHandle model = modelRuntime.loadFromAssetRepo(modelFileName);

// Or resolve the full path manually
String fullPath = FileManagement.resolveAssetPath("Hand.obj");
TextFile file = FileManagement.loadAssetFile("Hand.obj");
```

### Expected Local Structure

After sync, your local repo should look like:

```
$IXDAR_ASSET_REPO_ROOT/
└── models/
    └── shared/
        └── versioned/
            └── hand/
                └── v1.1.0/
                    └── Hand.obj
```

For the default test scene (`Hand.obj`), you can place it at:

```
$IXDAR_ASSET_REPO_ROOT/Hand.obj
```

Or in the versioned structure:

```
$IXDAR_ASSET_REPO_ROOT/models/shared/versioned/hand/v1.1.0/Hand.obj
```

## 4. Error Handling

### Missing Environment Variable

If `IXDAR_ASSET_REPO_ROOT` is not set:

```
Exception in thread "main" java.lang.IllegalStateException: 
Missing asset repo root. Set either env var IXDAR_ASSET_REPO_ROOT 
or JVM property ixdar.asset.repo.root (e.g. C:\Code\IxdarAssets).
```

### Missing Asset File

If the asset file does not exist at the resolved path:

```
Exception in thread "main" java.io.IOException: 
External asset file not found: C:\Code\IxdarAssets\Hand.obj
```

## 5. Versioning Strategy

### Semantic Versioning

- Use semantic versioning (MAJOR.MINOR.PATCH) for asset versions
- Major version changes: Breaking format changes, incompatible geometry
- Minor version changes: Additive changes, backward-compatible
- Patch version changes: Bug fixes, minor improvements

### Latest Alias

For convenience, maintain a `latest` alias pointing to the most recent version:

```bash
# Create symlink (Unix/macOS)
ln -s v1.1.0 latest

# Or use S3 redirect (S3)
aws s3api put-object-copy --bucket ixdar-assets \
    --key models/shared/versioned/hand/latest/Hand.obj \
    --copy-source ixdar-assets/models/shared/versioned/hand/v1.1.0/Hand.obj
```

## 6. CI/CD Integration

### GitHub Actions Example

```yaml
- name: Sync assets from S3
  run: |
    aws s3 sync s3://ixdar-assets/models/shared/versioned/ ./assets/
```

### Maven Profile Example

```xml
<profile>
    <id>asset-sync</id>
    <activation>
        <property>
            <name>env.IXDAR_ASSET_REPO_ROOT</name>
        </property>
    </activation>
    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <id>sync-assets</id>
                        <phase>process-resources</phase>
                        <goals>
                            <goal>exec</goal>
                        </goals>
                        <configuration>
                            <executable>./tools/sync-assets.sh</executable>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

## 7. Testing

### Manual Test

1. Set `IXDAR_ASSET_REPO_ROOT` to your local asset directory
2. Ensure `Hand.obj` exists at `$IXDAR_ASSET_REPO_ROOT/Hand.obj`
3. Run the `ModelLoadScene` and verify the hand model loads correctly
4. Check console output for model stats (vertices, triangles, center, radius)

### Negative Tests

1. **Missing env var**: Unset `IXDAR_ASSET_REPO_ROOT` and verify clear error message
2. **Missing file**: Remove `Hand.obj` and verify missing-file error
