# Creates a CSV file that contains the categories together with the relevant data from the full dataset.
import csv, os

with open('categories.csv', 'r', newline='') as categoriesfile, open('full_dataset.csv', 'r', newline='') as datasetfile, open('merged.csv', 'w', newline='') as outputfile:
    categories = csv.reader(categoriesfile)
    dataset = csv.reader(datasetfile)
    output = csv.writer(outputfile)

    # Remove header
    next(dataset)
    
    # Write the header of the new dataset
    output.writerow["Project", "Pull Number", "Pull type", "ID", "Category"]

    categoriesMap = dict()
    idMap = dict()

    #Store all categories in a map to easily access them
    for row in categories:
        categoriesMap[(row[0], row[1])] = row[2]
    
    for row in dataset:
        output.writerow([row[0], row[1], row[2], row[3], categoriesMap[(row[0], row[1])]])
    
    