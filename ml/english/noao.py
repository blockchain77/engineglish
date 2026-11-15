import sys, runpy
sys.modules['torchao'] = None  # peft 0.21 rejects torchao 0.15 in this container -> treat as absent
script = sys.argv[1]; sys.argv = sys.argv[1:]
runpy.run_path(script, run_name='__main__')
