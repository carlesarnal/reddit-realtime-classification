import torch
import joblib
import json
import pickle
from transformers import AutoTokenizer, AutoModelForSequenceClassification

# === Transformer Model ===
transformer_model_dir = "./reddit_flair_classifier"

# Load model, tokenizer and label encoder
transformer_model = AutoModelForSequenceClassification.from_pretrained(transformer_model_dir)
tokenizer = AutoTokenizer.from_pretrained(transformer_model_dir)
transformer_label_encoder = joblib.load(f"{transformer_model_dir}/reddit_flair_label_encoder.joblib")

transformer_model.eval()
device = torch.device("cpu")
transformer_model.to(device)

# === Logistic Regression Model ===
with open("vectorizer.pkl", "rb") as f:
    tfidf = pickle.load(f)

with open("LSA_topics.pkl", "rb") as f:
    tsvd = pickle.load(f)

with open("reddit_classifier.pkl", "rb") as f:
    classifier = pickle.load(f)

logistic_flairs = ['Work', 'Misc', 'Food', 'Personal', 'Meta', 'Sports', 'Travel', 'Politics', 'Culture', 'History', 'Education', 'Language', 'Foreign']

# === Sample Reddit Post ===
post = {
    "id": "1jpfui4",
    "title": "Daily Slow Chat",
    "content": """Hi there!

Welcome to our daily scheduled post, the **Daily Slow Chat.**

If you want to just chat about your day, if you have questions for the moderators *(please mark these [Mod] so we can find them)*, or if you just want talk about oatmeal then this is the thread for you!

Enjoying the small talk? We have a Discord server too! We'd love to have more of you over there. Do both of us a favour [and use this link to join the fun](https://discord.gg/BTX7cK3R4k).

The mod-team wishes you a nice day!"""
}

# === Transformer Inference ===
inputs = tokenizer(post["content"], return_tensors="pt", truncation=True, padding=True, max_length=512)
inputs = {k: v.to(device) for k, v in inputs.items()}

with torch.no_grad():
    outputs = transformer_model(**inputs)
    probs = torch.nn.functional.softmax(outputs.logits, dim=-1)
    transformer_pred_class = torch.argmax(probs, dim=1).item()
    transformer_flair = str(transformer_label_encoder.classes_[transformer_pred_class])
    transformer_confidence = float(probs[0][transformer_pred_class])

# === Logistic Regression Inference ===
X = tfidf.transform([post["content"]])
X = tsvd.transform(X)
logistic_pred_class = classifier.predict(X)[0]
logistic_probs = classifier.predict_proba(X)[0]
logistic_confidence = max(logistic_probs)
logistic_flair = logistic_flairs[logistic_pred_class]

# === Output Comparison ===
print(json.dumps({
    "id": post["id"],
    "title": post["title"],
    "transformer_prediction": {
        "flair": transformer_flair,
        "confidence": transformer_confidence
    },
    "logistic_regression_prediction": {
        "flair": logistic_flair,
        "confidence": logistic_confidence
    },
    "agreement": transformer_flair == logistic_flair
}, indent=2))