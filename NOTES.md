# Learning notes

Implementation started from the uploaded “FlashSale: Build Guide, From Basics to Advanced”. The first milestone prioritizes a correct atomic checkout, then adds idempotency. The deliberately naive version and alternative lock strategies remain separate learning tasks.

Questions to answer while reading:
- Why can final stock be nonnegative even if a naive implementation oversells?
- Why do inventory, the order and the idempotency key need one transaction?
- Why does the same-key insert wait, and why does the next READ COMMITTED statement see the winner?
- Why must concurrency tests retrieve every Future?
- Which metrics would distinguish quick sold-out responses from successful checkout latency?

Add your own explanation and questions after running and modifying the code. Do not treat generated notes as evidence of personal understanding or measured results.
