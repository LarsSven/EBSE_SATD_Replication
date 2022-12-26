import csv

with open('original.csv', 'r', newline='') as csvfile, open('new.csv', 'w', newline='') as output:
    reader = csv.reader(csvfile)
    writer = csv.writer(output)

    header = next(reader)
    writer.writerow(header)
    
    for row in reader:
        if(row[5] != "non_debt" and row[0] != "infrastructure-puppet"):
            writer.writerow(row)