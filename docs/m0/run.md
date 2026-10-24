# M0 run

layer 0 (scenario 0): 27 instances (3456 bytes retained; 4096000 bytes as one RGBA8 buffer of its pixels), 1280x800
layer 1 (scenario 1): 1060 instances (135680 bytes retained; 1934400 bytes as one RGBA8 buffer of its pixels), 780x620; first batch 134252 bytes, 1017 ops, program 68.32 ms, apply 1.214 ms
layer 2 (scenario 2): 55 instances (7040 bytes retained; 504000 bytes as one RGBA8 buffer of its pixels), 420x300; first batch 7136 bytes, 54 ops, program 75.90 ms, apply 0.074 ms
layer 3 (scenario 3): 367 instances (46976 bytes retained; 707200 bytes as one RGBA8 buffer of its pixels), 340x520; first batch 48452 bytes, 367 ops, program 36.69 ms, apply 1.118 ms

| metric | still | typing | animating | moving | all at once |
|---|---|---|---|---|---|
| frames | 348 | 348 | 348 | 349 | 348 |
| fps | 57.9 | 58.0 | 58.1 | 58.2 | 57.8 |
| frame interval p50/p95/max ms | 17.16 / 17.56 / 26.44 | 17.17 / 17.58 / 21.16 | 17.15 / 17.39 / 21.54 | 17.15 / 17.57 / 21.12 | 17.13 / 19.70 / 33.84 |
| compositor busy per frame p50/p95/max ms | 0.01 / 0.01 / 3.08 | 0.01 / 1.12 / 3.02 | 1.44 / 2.48 / 4.51 | 4.87 / 6.21 / 9.71 | 6.32 / 8.82 / 12.81 |
| engine copy to swapchain + present p50/p95/max ms | 17.15 / 17.54 / 26.43 | 17.11 / 17.42 / 20.21 | 15.72 / 16.22 / 20.26 | 12.30 / 13.18 / 15.70 | 10.78 / 11.79 / 27.90 |
| frames with damage | 0 | 70 | 345 | 349 | 348 |
| damaged px per damaged frame | 0 | 414 | 13061 | 167237 | 238277 |
| raster p50/p95/max ms (damaged frames) | 0.00 / 0.00 / 0.00 | 0.66 / 1.91 / 2.35 | 1.34 / 2.30 / 4.46 | 4.86 / 6.20 / 9.70 | 6.20 / 8.47 / 12.12 |
| vertices drawn per damaged frame | 0 | 11883 | 27453 | 7315 | 17588 |
| instance upload bytes per frame | 0 | 653 | 4391 | 12 | 5075 |
| wakes sent | 0 | 70 | 346 | 0 | 418 |
| batches | 0 | 70 | 345 | 1 | 417 |
| no-change answers | 0 | 0 | 0 | 0 | 0 |
| messages per frame | 0.00 | 0.40 | 1.99 | 0.00 | 2.40 |
| batch bytes p50/p95/max | 0 / 0 / 0 | 404 / 536 / 2780 | 1064 / 1328 / 2120 | 932 / 932 / 932 | 1064 / 1328 / 3116 |
| ops per batch (mean) | 0 | 3.0 | 8.3 | 7.0 | 7.6 |
| apply on arrival p50/p95 ms | 0.000 / 0.000 | 0.019 / 0.031 | 0.008 / 0.028 | 0.002 / 0.002 | 0.002 / 0.008 |
| program work p50/p95 ms | 0.00 / 0.00 | 0.77 / 3.92 | 0.11 / 2.22 | 0.10 / 0.10 | 0.10 / 1.06 |
| wake to present p50/p95/max ms | 0.00 / 0.00 / 0.00 | 34.33 / 36.48 / 51.92 | 32.97 / 34.05 / 51.45 | 34.67 / 34.67 / 34.67 | 28.09 / 31.37 / 47.18 |
| landed in TTF frame | - | 70 of 70 (100.0%) | 345 of 345 (100.0%) | 1 of 1 (100.0%) | 416 of 417 (99.8%) |
| late by >= 1 frame | 0 | 0 | 0 | 0 | 1 |
| present-time prediction error p50/p95 ms (abs) | 0.00 / 0.00 | 0.16 / 1.63 | 0.09 / 1.07 | 1.43 / 1.43 | 0.31 / 5.29 |
| batches held for a later TTF frame | 0 | 1 | 2 | 0 | 0 |
| ignored batches (D5) | 0 | 0 | 11 | 0 | 12 |
| inputs coalesced behind an outstanding wake | 0 | 0 | 0 | 0 | 0 |
