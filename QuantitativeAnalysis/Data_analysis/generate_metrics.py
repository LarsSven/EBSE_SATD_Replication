# Creates a CSV file that contains the categories together with the relevant data from the full dataset.
import csv, glob, pathlib, lizard
from collections import defaultdict
from os import path

def calculateLizardComplexity(file):
    complexity = 0

    for function in lizard.analyze_file(file).function_list:
        complexity += function.cyclomatic_complexity
    
    return complexity

def calculateComplexity(file):
    diff = 0
    amountOfFiles = 0

    before = calculateLizardComplexity(file)
    afterPath = file.replace("/before/", "/after/", 1)
    if(path.exists(afterPath)):
        after = calculateLizardComplexity(afterPath)
        if before != 0 and after != 0:
            amountOfFiles = 1
            diff = (before - after)
    else:
        print(file, "does not have an after version.")
    
    return (diff, amountOfFiles)

def appendComplexity(project):
    unhandledExtensions = defaultdict(int)

    diff = 0
    amountOfFiles = 0

    for file in glob.iglob(project + "/before/" + '**/*.*', recursive=True):
        if not path.isfile(file): # Some projects have folders with names like "options.java"
            continue

        suffix = pathlib.Path(file).suffix

        if suffix in [".py", ".java", ".scala", ".go", ".c", ".cc", ".cpp", ".js", ".jsx", ".ts", ".tsx", ".erb"]:
            (newDiff, newFiles) = calculateComplexity(file)
            diff += newDiff
            amountOfFiles += newFiles
        else:
            unhandledExtensions[pathlib.Path(file).suffix] += 1

    return [dict(unhandledExtensions), amountOfFiles, diff]

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
    output.writerow(["Project", "Pull Number", "Pull type", "ID", "Category", "Diffs available", "LOC Added", "LOC Removed", "Files changed", "Files excluded", "Files included", "Mean complexity difference"])

    for row in dataset:
        # Diffs are stored as "/project/pull_number/id"
        file = f"../CodeChanges/{row[0]}/{row[1]}/{row[3]}"
        if path.exists(file):
            row.append("Yes")
            row.extend(appendDiffs(file + "/changes.diff"))
            row.extend(appendComplexity(file))
        else:
            row.append("No")
        output.writerow(row)