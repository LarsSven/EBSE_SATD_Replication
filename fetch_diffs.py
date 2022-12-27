import csv, collections, os, os.path, sys, shutil, requests, json, time


IN_FILE = 'Preprocessing/new.csv'
OUT_DIR = 'CodeChanges'

API_TOKEN = os.environ['API_TOKEN']
HEADERS = {
    'Authorization': f'Bearer {API_TOKEN}',
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28'
}

if os.path.exists(OUT_DIR):
    print(f'Output path {OUT_DIR} already exists. Continue? (y/N)')
    if input() != 'y':
        sys.exit()

with open(IN_FILE, 'r') as csvfile:
    reader = csv.DictReader(csvfile)

    repo_pull_items = collections.defaultdict(list)

    for row in reader:
        repo_pull_items[(row['project'], row['pull_number'])].append(row)

n_pulls = len(repo_pull_items)

print('Fetching pull request data')

for i, ((repo, pull_number), items) in enumerate(repo_pull_items.items()):
    print(f'\rProcessing {i+1}/{n_pulls}', end='', flush=True)

    pull_path = f'{OUT_DIR}/{repo}/{pull_number}'

    os.makedirs(pull_path, exist_ok=True)

    # Fetch or read PR data

    if not os.path.exists(f'{pull_path}/data.json'):
        pull_response = requests.get(
            f'https://api.github.com/repos/apache/{repo}/pulls/{pull_number}',
            headers=HEADERS
        )
        pull_response.raise_for_status()

        pull_data = pull_response.json()

        with open(f'{pull_path}/data.json', 'w') as pull_data_file:
            json.dump(pull_data, pull_data_file, indent=2)
    else:
        with open(f'{pull_path}/data.json', 'r') as pull_data_file:
            pull_data = json.load(pull_data_file)

    # Fetch diffs if they don't exist

    if not os.path.exists(f'{pull_path}/changes.diff'):
        diff_response = requests.get(pull_data['diff_url'], headers=HEADERS)
        diff_response.raise_for_status()

        with open(f'{pull_path}/changes.diff', 'w') as diff_data_file:
            diff_data_file.write(diff_response.text)

print('\nDone')
