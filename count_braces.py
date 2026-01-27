with open('app/src/main/java/com/facelapse/app/ui/project/ProjectViewModel.kt', 'r') as f:
    content = f.read()

count = 0
for i, char in enumerate(content):
    if char == '{':
        count += 1
    elif char == '}':
        count -= 1

    if count < 0:
        print(f"Extra closing brace at char {i}")

if count > 0:
    print(f"Missing {count} closing braces")
elif count == 0:
    print("Braces are balanced")
