# Contributing

This project catalogs and demonstrates self-healing patterns for financial transaction infrastructure. Contributions are welcome, particularly:

- **Field experience.** If you operate payment or transaction systems and have seen a failure mode this taxonomy misses, or lived one of the documented families differently, open an issue describing it. Generalized descriptions only: no proprietary details, internal tool names, or confidential figures.
- **Pattern proposals.** New detection or recovery patterns should follow the schema used in `docs/patterns/`: detection signal, decision policy, recovery action, and a failure injection scenario demonstrating the loop.
- **Reference implementation.** Code contributions should keep the implementation minimal and readable. It exists to demonstrate patterns, not to be a production system.

## Ground rules

- All contributions must be original work or properly attributed public material.
- The reference implementation processes synthetic transactions only. Do not add real payment credentials, card data, or any integration with live financial systems.
- Keep discussions technical and vendor-neutral.

## Getting started

Open an issue before large changes. For small fixes (typos, clarifications), a pull request directly is fine.
