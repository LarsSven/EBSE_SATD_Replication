# Creates a CSV file that contains the categories together with the relevant data from the full dataset.
import csv
from os import path

def appendDiffs(filename):
    LOC_added = 0
    LOC_removed = 0
    files_changed = 0

    with open(filename) as file:
        for line in file:
            if line.startswith("diff --git"):
                files_changed += 1
            elif line.startswith('+') and not line.startswith("+++"): ##Include LOC but not full files
                LOC_added += 1
            elif line.startswith('-') and not line.startswith("---"): ##Include LOC but not full files
                LOC_removed += 1
    return [LOC_added, LOC_removed, files_changed]

with open('merged.csv', 'r', newline='') as inputfile, open('output.csv', 'w', newline='') as outputfile:
    dataset = csv.reader(inputfile)
    output = csv.writer(outputfile)

    # Remove header
    next(dataset)

    # Write the header of the new dataset
    output.writerow(["Project", "Pull Number", "Pull type", "ID", "Category", "Diffs available", "LOC Added", "LOC Removed", "Files changed"])

    for row in dataset:
        # Diffs are stored as "/project/pull_number/id"
        file = f"../CodeChanges/{row[0]}/{row[1]}/{row[3]}"
        if path.exists(file):
            row.append("Yes")
            row.extend(appendDiffs(file + "/changes.diff"))
        else:
            row.append("No")
        output.writerow(row)