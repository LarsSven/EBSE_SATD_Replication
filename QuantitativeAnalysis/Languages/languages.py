import csv, os, requests

API_TOKEN = os.environ['API_TOKEN']
HEADERS = {
    'Authorization': f'Bearer {API_TOKEN}',
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28'
}

with open('../Comments_Review/AllCategories.csv', 'r') as infile, open('languages.csv', 'w') as outfile:
    reader = csv.reader(infile)
    writer = csv.DictWriter(outfile, ['project', 'languages'])
    writer.writeheader()

    projects = {row[0] for row in reader}

    for project in projects:
        print(project)

        response = requests.get(f'https://api.github.com/repos/apache/{project}/languages', headers=HEADERS)
        response.raise_for_status()

        writer.writerow({
            'project': project,
            'languages': response.json()
        })
