# EBSE Replication Package

This repository contains code and data to reproduce the results of our research project about
*Self-Admitted Technical Debt in Pull Requests* for the course Evidence-Based Software Engineering.

## 0. Install prerequisites

```console
$ pip install -U requests lizard scipy scikit-learn
```

## 1. Preprocess original dataset

```console
$ cd Preprocessing
$ python preprocess.py
$ cp new.csv ../QualitativeAnalysis/Round1/sampling_input.csv
```

## 2. Perform sampling for qualitative analysis rounds

### Round 1 and 2:

```console
$ cd ../QualitativeAnalysis/Round<x>
$ python ../subsample.py
$ cp nonsampled.csv ../Round<x+1>/sampling_input.csv
```

### Round 3:

```console
$ cd ../Round3
```

In `subsample.py`, set `NUM_SAMPLES` 172 (1/3rd of the total).
Then, sample for each of the researchers (Lars, Germán, Koen):
```
$ python ../subsample.py
```
Use the `nonsampled.csv` output as the input file for the next researcher.

### Round 4 (verification):

Set `NUM_SAMPLES` to 17. Then sample from `Round3/sampled_{Lars,German,Koen}.csv`:
```console
$ cd ../Round4
$ python ../subsample.py
```

## 3. Calculate kappa score

```console
$ cd Agreement
$ python kappa.py
```

## 4. Retrieve code changes

```console
$ cd ../../QuantitativeAnalysis
$ python codechanges.py
```

## 5. Retrieve project languages

```console
$ cd Languages
$ python languages.py
```

## 6. Merge dataset with categories

```console
$ cd ../Data_analysis
$ python merge_categories.py
```

## 7. Generate raw data for source code analysis

```console
$ python generate_metrics.py
```

## 8. Perform statistical significance tests

```console
$ cd ../Statistical_Significance
$ python levene.py
$ python ttest.py
```
