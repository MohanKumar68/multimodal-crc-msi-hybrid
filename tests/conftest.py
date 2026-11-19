import sys
from pathlib import Path

# Add src directory to Python path for imports
src_dir = Path(__file__).parents[2] / "src"
if str(src_dir) not in sys.path:
    sys.path.insert(0, str(src_dir))

# Also add src/api for convenience
api_dir = src_dir / "api"
if str(api_dir) not in sys.path:
    sys.path.insert(0, str(api_dir))

# Configure logging for tests
import logging
logging.basicConfig(level=logging.INFO)
