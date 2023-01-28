import csv
from sklearn.metrics import cohen_kappa_score


def print_kappa(filename):
    with open(filename, 'r') as csvfile:
        reader = csv.DictReader(csvfile)
        rows = list(reader)
        names = reader.fieldnames

        data = {
            name: [row[name] for row in rows]
            for name in names
        }

        for i in range(len(names)):
            for j in range(i + 1, len(names)):
                n1 = names[i]
                n2 = names[j]
                kappa = cohen_kappa_score(data[n1], data[n2])
                print(f'{n1} - {n2}: {kappa:.3f}')


for file in ['round1.csv', 'round2.csv', 'round4.csv']:
    print(file)
    print_kappa(file)
