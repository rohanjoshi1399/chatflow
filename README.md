# ChatFlow - Scalable WebSocket Chat System
## CS6650 Assignment 1

Complete implementation of a distributed chat system.

## Quick Start

### Import into IntelliJ IDEA

1. Open IntelliJ IDEA
2. File → New → Project from Version Control
3. Select Git
4. Enter `https://github.khoury.northeastern.edu/rohki13/cs6650-assignment1.git`
5. Select a directory to clone the project into
6. Click `Next`
7. Select `Import project from external model` and click `Next`
8. Select `Maven` and click `Next`
9. Click `Finish`

### Build Projects
```bash
# Server
cd server
mvn clean package

# Client
cd client
mvn clean package
```