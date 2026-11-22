"""Assert database ownership without printing credentials or granting extra privileges."""
import subprocess

def execute(script):
    return subprocess.run(['docker', 'compose', 'exec', '-T', 'mysql', 'bash', '-c', script],
                          check=True, capture_output=True, text=True).stdout.strip()
for user, database, variable in [('auth','auth_db','AUTH'),('pet','core_db','CORE'),('document','document_db','DOCUMENT'),('notification','notification_db','NOTIFICATION')]:
    result=execute(f'export MYSQL_PWD="$MYSQL_{variable}_PASSWORD"; mysql -u{user} --batch --skip-column-names -e "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN (\'information_schema\', \'performance_schema\')"')
    assert result == database, (user, result)
    foreign='core_db' if user=='auth' else 'auth_db'
    execute(f'export MYSQL_PWD="$MYSQL_{variable}_PASSWORD"; if mysql -u{user} -e "USE {foreign}" >/dev/null 2>&1; then exit 1; fi')
    print(f'{user}: owns {database}; foreign database access rejected')
