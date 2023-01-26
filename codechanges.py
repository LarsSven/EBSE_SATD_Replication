import csv, os, requests, tarfile, multiprocessing

API_TOKEN = os.environ['API_TOKEN']
HEADERS = {
    'Authorization': f'Bearer {API_TOKEN}',
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28'
}


def download_archive(project, sha, path):
    response = requests.get(f'https://github.com/apache/{project}/archive/{sha}.tar.gz', headers=HEADERS)
    response.raise_for_status()
    with open(path, 'wb') as f:
        f.write(response.content)


def process_item(row):
    project, pull_number, id, _url, before, after = row

    print(f'Processing {project}#{pull_number} ({id})')

    path = f'CodeChanges/{project}/{pull_number}/{id}'

    if before == 'None' or after == 'None':
        return

    if os.path.exists(path):
        return

    os.makedirs(path, exist_ok=True)

    compare_url = f'https://api.github.com/repos/apache/{project}/compare/{before}...{after}'
    compare_response = requests.get(compare_url, headers=HEADERS)
    compare_response.raise_for_status()
    compare = compare_response.json()

    # check if the project was renamed
    if compare['url'] != compare_url:
        project, _ = compare['url'].removeprefix('https://api.github.com/repos/apache/').split('/', maxsplit=1)

    diff_response = requests.get(compare['diff_url'], headers=HEADERS)
    diff_response.raise_for_status()

    with open(f'{path}/changes.diff', 'w') as diff_file:
        diff_file.write(diff_response.text)

    download_archive(project, before, f'{path}/before.tar.gz')
    download_archive(project, after, f'{path}/after.tar.gz')

    with tarfile.open(f'{path}/before.tar.gz', 'r') as before_archive, tarfile.open(f'{path}/after.tar.gz', 'r') as after_archive:
        for file in compare['files']:
            if file['status'] in ('modified', 'removed'):
                before_archive.extract(f'{project}-{before}/' + file['filename'], path)
            elif file['status'] == 'renamed':
                before_archive.extract(f'{project}-{before}/' + file['previous_filename'], path)

            if file['status'] in ('modified', 'added', 'renamed'):
                after_archive.extract(f'{project}-{after}/' + file['filename'], path)

    os.remove(f'{path}/before.tar.gz')
    os.remove(f'{path}/after.tar.gz')
    if os.path.exists(f'{path}/{project}-{before}'):
        os.rename(f'{path}/{project}-{before}', f'{path}/before')
    if os.path.exists(f'{path}/{project}-{after}'):
        os.rename(f'{path}/{project}-{after}', f'{path}/after')


with open('CodeChanges_Commits/commits.csv', 'r') as csvfile:
    reader = csv.reader(csvfile)

    next(reader)

    items = list(reader)

with multiprocessing.Pool(8) as p:
    p.map(process_item, items)
