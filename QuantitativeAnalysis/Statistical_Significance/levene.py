import csv
from scipy.stats import levene

def readColumns(reader):
    columns = [[] for _ in range(13)]

    for row in reader:
        for idx, value in enumerate(row):
            if value != '':
                columns[idx].append(int(value))

    return columns

with open('Complexity_Diff.csv', 'r', newline='') as csvfile:
    reader = csv.reader(csvfile)

    next(reader)

    columns = readColumns(reader)
    stat, p = levene(columns[0], columns[1], columns[2], columns[3], columns[4], columns[5], columns[6], columns[7], columns[8], columns[9], columns[10], columns[11], columns[12])
    print(stat, p)