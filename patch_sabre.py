import sys

file_path = '/usr/share/php/Sabre/DAV/Tree.php'
try:
    with open(file_path, 'r') as f:
        content = f.read()
except Exception as e:
    print('Not found')
    sys.exit(0)

old_code = '''    public function move($sourcePath, $destinationPath) {

        list($sourceDir, $sourceName) = URLUtil::splitPath($sourcePath);
        list($destinationDir, $destinationName) = URLUtil::splitPath($destinationPath);

        if ($sourceDir===$destinationDir) {
            $renameable = $this->getNodeForPath($sourcePath);
            $renameable->setName($destinationName);
        } else {
            $this->copy($sourcePath,$destinationPath);
            $this->getNodeForPath($sourcePath)->delete();
        }
        $this->markDirty($sourceDir);
        $this->markDirty($destinationDir);

    }'''

new_code = '''    public function move($sourcePath, $destinationPath) {

        list($sourceDir, $sourceName) = URLUtil::splitPath($sourcePath);
        list($destinationDir, $destinationName) = URLUtil::splitPath($destinationPath);

        if ($sourceDir===$destinationDir) {
            $renameable = $this->getNodeForPath($sourcePath);
            $renameable->setName($destinationName);
        } else {
            $sourceNode = $this->getNodeForPath($sourcePath);
            $destinationParent = $this->getNodeForPath($destinationDir);
            $fastMove = false;
            
            if ($sourceNode instanceof \\Sabre\\DAV\\FS\\Node && $destinationParent instanceof \\Sabre\\DAV\\FS\\Node) {
                try {
                    $refPath = new \\ReflectionProperty('\\Sabre\\DAV\\FS\\Node', 'path');
                    $refPath->setAccessible(true);
                    $realSource = $refPath->getValue($sourceNode);
                    $realDestParent = $refPath->getValue($destinationParent);
                    $realDest = rtrim($realDestParent, '/') . '/' . $destinationName;
                    
                    if (@rename($realSource, $realDest)) {
                        $fastMove = true;
                    }
                } catch (\\Exception $e) {
                }
            }

            if (!$fastMove) {
                $this->copy($sourcePath,$destinationPath);
                $this->getNodeForPath($sourcePath)->delete();
            }
        }
        $this->markDirty($sourceDir);
        $this->markDirty($destinationDir);

    }'''

if old_code in content:
    content = content.replace(old_code, new_code)
    with open(file_path, 'w') as f:
        f.write(content)
    print('PATCHED')
else:
    print('NOT FOUND OR ALREADY PATCHED')
