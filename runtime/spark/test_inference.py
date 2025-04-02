import torch
import joblib
import json
from transformers import AutoTokenizer, AutoModelForSequenceClassification

# Path to local model
model_dir = "./reddit_flair_classifier"

# Load model + tokenizer + label encoder
model = AutoModelForSequenceClassification.from_pretrained(model_dir)
tokenizer = AutoTokenizer.from_pretrained(model_dir)
label_encoder = joblib.load(f"{model_dir}/reddit_flair_label_encoder.joblib")

model.eval()
device = torch.device("cpu")
model.to(device)

# Sample input (from your example)
post = {
    "id": "1jpfui4",
    "title": "Daily Slow Chat",
    "content": """Hi there!

Welcome to our daily scheduled post, the **Daily Slow Chat.**

If you want to just chat about your day, if you have questions for the moderators *(please mark these [Mod] so we can find them)*, or if you just want talk about oatmeal then this is the thread for you!

Enjoying the small talk? We have a Discord server too! We'd love to have more of you over there. Do both of us a favour [and use this link to join the fun](https://discord.gg/BTX7cK3R4k).

The mod-team wishes you a nice day!"""
}

# Tokenize
inputs = tokenizer(post["content"], return_tensors="pt", truncation=True, padding=True, max_length=512)
inputs = {k: v.to(device) for k, v in inputs.items()}

# Predict
with torch.no_grad():
    outputs = model(**inputs)
    probs = torch.nn.functional.softmax(outputs.logits, dim=-1)
    pred_class = torch.argmax(probs, dim=1).item()
    classes = list(label_encoder.classes_)
    predicted_flair = str(classes[pred_class])
    confidence = float(probs[0][pred_class])

# Output
print(json.dumps({
    "id": post["id"],
    "title": post["title"],
    "predicted_flair": predicted_flair,
    "confidence": confidence
}, indent=2))
