# TRYST Splash Screen Implementation

## 1. Product Splash Configuration
The standard Eclipse RCP splash screen for TRYST is configured in `product/eu.kalafatic.tryst.product/tryst.product` and declared in `plugins/eu.kalafatic.tryst.application/plugin.xml`:

```xml
<splash
   location="eu.kalafatic.tryst.application"
   startupProgressRect="40,240,420,15"
   startupMessageRect="40,220,420,20"
   startupForegroundColor="94A3B8" />
```

## 2. Asset Characteristics
- **File**: `splash.bmp` located in the root of bundle `eu.kalafatic.tryst.application`.
- **Dimensions**: 500x330 pixels.
- **Color Depth**: 24-bit RGB Windows Bitmap (BMP) compatible with native OS platform loaders.
- **Visual Design**: Dark sleek background (`#181c24`), blue/indigo gradient accent bar, stylized dual polygon logo, product branding ("TRYST – AI Development Environment"), subtitle, version, and copyright notice.

## 3. Packaging & Lifecycle
- `splash.bmp` is explicitly listed in `plugins/eu.kalafatic.tryst.application/build.properties` in `bin.includes`.
- During startup, Equinox native launcher displays `splash.bmp` immediately.
- The splash screen remains visible while OSGi bundles resolve and the lifecycle coordinator completes initial phases.
- Once `PlatformUI.createAndRunWorkbench(...)` completes window initialization and renders the administration perspective, the splash screen closes automatically without blocking background threads.
