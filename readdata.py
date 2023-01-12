import csv, os

with open('sampled.csv', 'r', newline='') as csvfile:
    reader = csv.reader(csvfile)
    header = next(reader)

    skip = 0 # Starting row in the dataset
    for _ in range(skip):
        next(reader)
    
    for row in reader:
        os.system('clear')
        print("Entry:", skip+1)
        print("Project:", row[0], "PR:", row[1])
        if row[2] == "review":
            print("URL:", f'https://github.com/apache/{row[0]}/pull/{row[1]}#discussion_r{row[3]}')
        else:
            print("URL:", f'https://github.com/apache/{row[0]}/pull/{row[1]}#issuecomment-{row[3]}')
        print("Pull_type:", row[2])
        print("Classification:", row[5])
        print("Indicator:", row[6])
        print("Text:", row[4])
        input("Press Enter to continue...")
        skip += 1