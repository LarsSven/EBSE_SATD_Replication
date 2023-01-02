from collections import defaultdict
import csv, math, random

def writeFiles(samplewriter, nonsamplewriter, sampledList, nonsampledList):
    for sample in sampledList:
        samplewriter.writerow(sample)

    for nonsample in nonsampledList:
        nonsamplewriter.writerow(nonsample)

def extractSamples(candidates, amount):
    samples = random.sample(candidates, k=amount)
    nonsampled = [x for x in candidates if not x in samples]
    return (samples, nonsampled)

with open('sampling_input.csv', 'r', newline='') as csvfile, open('sampled.csv', 'w', newline='') as sampled, open('nonsampled.csv', 'w', newline='') as nonsampled:
    reader = csv.reader(csvfile)
    samplewriter = csv.writer(sampled)
    nonsamplewriter = csv.writer(nonsampled)

    # Handle the header describing the meaning of each column
    header = next(reader)
    samplewriter.writerow(header)
    nonsamplewriter.writerow(header)

    # Assign rows to their respective categories.
    # Categories are based on pull_type and classification
    categories = defaultdict(list)
    for row in reader:
        categories[(row[2], row[5])].append(row)

    # Total amount of pull requests in the dataset
    totalAmount = sum([len(categories[x]) for x in categories])

    # Lists will store all rows that were sampled and all that weren't
    sampledList = []
    nonsampledList = []

    # Deals with floating points (you cannot extract 3.4 pull requests from a category)
    # Does so by flooring the amount extracted (so 3 for 3.4), but then adding 0.4 to a count
    # Whenever the count goes above 1, that category gets an extra element extracted
    remainder = 0
    for category in categories:
        # Get all pull requests in the category
        pull_requests = categories[category]

        # Calculate how many samples should be extracted from the category
        contribution = len(pull_requests) / totalAmount * 173
        toSample = math.floor(contribution)
        remainder += contribution - math.floor(contribution)

        # 0.9999 to account for floating point issues causing the 100th pull request to not be sampled
        if remainder >= 0.9999:
            toSample += 1
            remainder -= 1

        (samples, nonsampled) = extractSamples(pull_requests, toSample)
        sampledList.extend(samples)
        nonsampledList.extend(nonsampled)
    
    writeFiles(samplewriter, nonsamplewriter, sampledList, nonsampledList)