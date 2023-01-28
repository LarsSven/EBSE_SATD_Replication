import csv
from scipy.stats import ttest_ind

def readColumns(reader):
    columns = [[] for _ in range(13)]

    for row in reader:
        for idx, value in enumerate(row):
            if value != '':
                columns[idx].append(int(value))

    return columns

with open('Complexity_Diff.csv', 'r', newline='') as csvfile, open('output.csv', 'w', newline='') as outputfile:
    reader = csv.reader(csvfile)
    writer = csv.writer(outputfile)

    categories = next(reader)
    writer.writerow([""] + categories)

    columns = readColumns(reader)
    for idx1, column1 in enumerate(columns):
        row = [categories[idx1]]
        for idx2, column2 in enumerate(columns):
            if idx1 != idx2:
                _, p = ttest_ind(column1, column2, equal_var=False)
                row.append(p)
            else:
                row.append("")
        writer.writerow(row)