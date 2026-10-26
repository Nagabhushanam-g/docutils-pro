"""Enable the legacy test key only in the isolated pytest process."""
import os
os.environ["CONVERSION_ALLOW_TEST_API_KEY"] = "true"
