# EBSE Replication Package

This repository contains code and data to reproduce the results of our research project about
*Self-Admitted Technical Debt in Pull Requests* for the course Evidence-Based Software Engineering.

## 0. Install prerequisites

```console
$ pip install -U requests lizard
```

## 1. Preprocess original dataset

```console
$ cd Preprocessing
$ python preprocess.py
$ cp new.csv ../QualitativeAnalysis/Round1/sampling_input.csv
```

## 2. Perform sampling for qualitative analysis rounds

Repeat for round 1 and 2:
```console
$ cd ../QualitativeAnalysis/Round<x>
$ python ../subsample.py
$ cp nonsampled.csv ../Round<x+1>/sampling_input.csv
```

Round 3:

Round 4:

## 3. Calculate kappa score

```console
$ cd Agreement
$ python kappa.py
```
