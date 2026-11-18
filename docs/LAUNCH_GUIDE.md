# TRYST — Launch Guide

| Launch target | How | Notes |
|---|---|---|
| **Development** | Eclipse → `.launches/TRYST_RCP_Application.launch` | PDE Runtime Workbench, workspace plugins + target platform |
| **Debug** | Eclipse → Debug Configurations → duplicate of `TRYST_RCP_Application` | |
| **Headless server** | Eclipse `.launches/TRYST_Headless_Server.launch` or `scripts/run-headless.sh` | |
| **Tests** | Eclipse `.launches/TRYST_Tests.launch` | PDE JUnit |
| **Product (built)** | `product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64/tryst` | requires build; GUI needs display |

UPM detects the built product launcher as `Product: tryst` (kind `eclipse-product`).

GUI launches were not verified headlessly on this host; the product launcher's existence/executability was verified after build.
