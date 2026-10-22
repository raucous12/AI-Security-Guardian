# Third-party data and notices

The source code in this repository is released under the MIT License (see [LICENSE](LICENSE)). The data files
listed below were produced from third-party sources and keep their original licences. Each bundled data file
starts with a header that names its source and, where it applies, the pinned upstream commit.

## Bundled in the app (`core/intel/src/main/resources/lian/intel/`)

| File | Source | Licence | Changes made |
|---|---|---|---|
| `psl.txt` | [Public Suffix List](https://publicsuffix.org/), Mozilla Foundation and contributors | [MPL-2.0](https://mozilla.org/MPL/2.0/) | Rules converted to a two-column format (ICANN or private section, rule); no rules changed |
| `confusables.tsv` | [Unicode confusables, UTS #39](https://www.unicode.org/reports/tr39/), © Unicode, Inc. | [Unicode License v3](https://www.unicode.org/license.txt) | Reduced to code points and their prototypes |
| `stalkerware.tsv` | [Echap stalkerware-indicators](https://github.com/AssoEchap/stalkerware-indicators) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | Selected fields (name, packages, certificate SHA-1s, websites) reformatted as TSV |
| `attestation_roots.pem`, `attestation_revoked.tsv` | Google, [Android key attestation](https://developer.android.com/privacy-and-security/security-key-attestation) root certificates and status list | Published by Google for verifying key attestation | Status list reduced to serial number and status |
| `domain.vgm`, `message.vgm` | Models trained by `:tools:trainer` from the corpora below | Derived from the datasets below | See [docs/ml-model-card.md](docs/ml-model-card.md) |

The other files in that folder (`brands.tsv`, `stores.tsv`, `os_support.tsv`, `tld_risk.tsv`, `shorteners.txt`,
`premium_numbers.tsv`, `script_tlds.tsv`) are curated by the maintainers and covered by the MIT License.

## Training corpus (`tools/trainer/corpus/`)

| File | Sources | Licence | Changes made |
|---|---|---|---|
| `messages.tsv.gz` | [UCI SMS Spam Collection](https://archive.ics.uci.edu/dataset/228/sms+spam+collection) (T. A. Almeida, J. M. Gómez Hidalgo, A. Yamakami, 2011); [SMS Phishing Dataset](https://doi.org/10.17632/f45bkkt8pr.1) (S. Mishra, D. Soni, Mendeley Data v1, 2022) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | Merged into `label<TAB>source<TAB>text` rows (spam and smishing = 1); exact duplicates across the two datasets merged; texts labelled both ways dropped; tabs and line breaks replaced by spaces |
| `domains.tsv.gz` | [Phishing.Database](https://github.com/Phishing-Database/Phishing.Database) (Mitchell Krog, Nissar Chababy), active domains at a pinned commit; [Majestic Million](https://majestic.com/reports/majestic-million) (Majestic-12 Ltd) | [MIT](https://github.com/Phishing-Database/Phishing.Database/blob/master/LICENSE); [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | Each domain reduced to its registrable label with the Public Suffix List; phishing hosts on Majestic-listed domains skipped; labels that appear in both lists dropped |
| `authored-messages.tsv` | Written by the maintainers | MIT | None |

Exact download URLs and SHA-256 pins for every source are in
[tools/trainer/datasets.properties](tools/trainer/datasets.properties).

## Libraries

The app depends only on Kotlin and Android Jetpack (AndroidX) libraries, which are licensed under the Apache License 2.0.
This includes the Material icons used in the interface.
