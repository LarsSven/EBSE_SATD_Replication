# Creates a CSV file that contains the categories together with the relevant data from the full dataset.
import csv, os

with open('categories.csv', 'r', newline='') as categoriesfile, open('full_dataset.csv', 'r', newline='') as datasetfile, open('merged.csv', 'w', newline='') as outputfile:
    categories = csv.reader(categoriesfile)
    dataset = csv.reader(datasetfile)
    output = csv.writer(outputfile)