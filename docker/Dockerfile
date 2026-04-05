# Dockerfile for Ixdar - Dockerized Build Environment for Parallel Agent Development
# This container provides a complete Ixdar development environment with:
# - Java 21 JDK
# - Maven for building
# - LWJGL native libraries for OpenGL
# - Xvfb for headless display rendering
# - Mesa llvmpipe for software OpenGL rendering

FROM openjdk:21-jdk-slim

# Set environment variables
ENV DEBIAN_FRONTEND=noninteractive
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV MAVEN_HOME=/opt/maven
ENV PATH=$PATH:$MAVEN_HOME/bin

# Install system dependencies
RUN apt-get update && apt-get install -y \
    # X11/Xvfb for headless display
    xvfb \
    # Mesa for software OpenGL rendering (llvmpipe)
    mesa-utils-extra \
    libgl1-mesa-glx \
    libgl1-mesa-dri \
    # Additional utilities
    wget \
    curl \
    unzip \
    x11-xserver-utils \
    # Clean up
    && rm -rf /var/lib/apt/lists/*

# Install Maven
RUN wget -q https://dlcdn.apache.org/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz -O /tmp/maven.tar.gz \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && rm /tmp/maven.tar.gz

# Create working directory
WORKDIR /ixdar

# Copy project files
COPY pom.xml .
COPY annotations/ annotations/
COPY ixdar-app/pom.xml ixdar-app/pom.xml
COPY ixdar-app/src ixdar-app/src

# Build the project (pre-built for faster container startup)
# The actual build happens in agent-env.sh
# RUN mvn clean package -P mesh-viewer -DskipTests

# Copy automation CLI
COPY ixdar_automation_cli ./ixdar_automation_cli
COPY pyproject.toml .
COPY uv.lock .

# Install Python dependencies for automation CLI
RUN pip install -e ./ixdar_automation_cli

# Default command - will be overridden by agent-env.sh
CMD ["/bin/bash"]
