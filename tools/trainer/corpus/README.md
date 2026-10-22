# Training corpus

These files are the processed training data for the two on-device models. They are rebuilt from pinned,
hash-checked downloads with:

```bash
./gradlew :tools:trainer:fetchCorpus      # downloads the sources in ../datasets.properties and checks their SHA-256
./gradlew :tools:trainer:trainModels      # retrains both models and rewrites docs/ml-model-card.md
```

| File | Built from | Licence |
|---|---|---|
| `messages.tsv.gz` | UCI SMS Spam Collection (Almeida, Gómez Hidalgo, Yamakami, 2011) and Mishra & Soni, SMS Phishing Dataset (Mendeley Data v1, doi:10.17632/f45bkkt8pr.1) | CC BY 4.0 |
| `domains.tsv.gz` | Phishing.Database (Mitchell Krog, Nissar Chababy) and Majestic Million (Majestic-12 Ltd) | MIT; CC BY 3.0 |
| `authored-messages.tsv` | Written by the maintainers (training only, never in the test split) | MIT |

The two `.gz` files hold `label<TAB>source<TAB>text` rows and `authored-messages.tsv` holds `label<TAB>text` rows
(lines starting with `#` are comments); the label is 1 for scam, spam or phishing and 0 for ordinary. The changes
made to the original datasets are listed in [THIRD_PARTY_NOTICES.md](../../../THIRD_PARTY_NOTICES.md).
