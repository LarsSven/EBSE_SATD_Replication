import csv, os

with open('sampled_German.csv', 'r', newline='') as csvfile:
    reader = csv.reader(csvfile)
    header = next(reader)

    skip = 0 # Starting row in the dataset
    for _ in range(skip):
        next(reader)
    
    for row in reader:
        #os.system('clear')
        #print("Entry:", skip+1)
        #print("Project:", row[0], "PR:", row[1], "URL:", f'https://github.com/apache/{row[0]}/pull/{row[1]}')
        #print("Pull_type:", row[2])
        #print("Classification:", row[5])
        #print("Indicator:", row[6])
        #print("Text:", row[4])
        #input("Press Enter to continue...")
        print(row[0])
        skip += 1